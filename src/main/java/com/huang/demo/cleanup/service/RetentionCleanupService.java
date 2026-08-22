package com.huang.demo.cleanup.service;

import com.huang.demo.common.compensation.domain.model.CompensationFailureType;
import com.huang.demo.common.compensation.service.CompensationService;
import com.huang.demo.cleanup.config.CleanupProperties;
import com.huang.demo.cleanup.domain.CleanupResult;
import com.huang.demo.excel.repository.StudentMapper;
import com.huang.demo.file.domain.entity.FileRecord;
import com.huang.demo.file.domain.entity.FileUploadTask;
import com.huang.demo.file.repository.FileRecordMapper;
import com.huang.demo.file.repository.FileUploadTaskMapper;
import com.huang.demo.file.service.FileObjectStorageService;
import com.huang.demo.common.lock.DistributedLockService;
import com.huang.demo.task.repository.AsyncTaskRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class RetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);
    private static final int MAX_VERSION_CLEANUP_ROUNDS_PER_RUN = 200;
    private static final int MAX_VERSION_CLEANUP_BATCH_SIZE = 50000;

    private final CleanupProperties properties;
    private final AsyncTaskRecordMapper asyncTaskRecordMapper;
    private final FileUploadTaskMapper fileUploadTaskMapper;
    private final FileRecordMapper fileRecordMapper;
    private final StudentMapper studentMapper;
    private final FileObjectStorageService fileObjectStorageService;
    private final DistributedLockService distributedLockService;
    private final CompensationService compensationService;

    @Autowired
    public RetentionCleanupService(CleanupProperties properties,
                                   AsyncTaskRecordMapper asyncTaskRecordMapper,
                                   FileUploadTaskMapper fileUploadTaskMapper,
                                   FileRecordMapper fileRecordMapper,
                                   StudentMapper studentMapper,
                                   FileObjectStorageService fileObjectStorageService,
                                   DistributedLockService distributedLockService,
                                   CompensationService compensationService) {
        this.properties = properties;
        this.asyncTaskRecordMapper = asyncTaskRecordMapper;
        this.fileUploadTaskMapper = fileUploadTaskMapper;
        this.fileRecordMapper = fileRecordMapper;
        this.studentMapper = studentMapper;
        this.fileObjectStorageService = fileObjectStorageService;
        this.distributedLockService = distributedLockService;
        this.compensationService = compensationService;
    }

    public RetentionCleanupService(CleanupProperties properties,
                                   AsyncTaskRecordMapper asyncTaskRecordMapper,
                                   FileUploadTaskMapper fileUploadTaskMapper,
                                   FileRecordMapper fileRecordMapper,
                                   StudentMapper studentMapper,
                                   FileObjectStorageService fileObjectStorageService,
                                   DistributedLockService distributedLockService) {
        this(properties, asyncTaskRecordMapper, fileUploadTaskMapper, fileRecordMapper,
                studentMapper, fileObjectStorageService, distributedLockService,
                CompensationService.noop());
    }

    @Scheduled(
            initialDelayString = "${app.cleanup.initial-delay-millis:300000}",
            fixedDelayString = "${app.cleanup.fixed-delay-millis:3600000}"
    )
    public void scheduledCleanup() {
        if (!properties.isEnabled()) {
            return;
        }
        cleanupOnceWithLock();
    }

    public CleanupResult cleanupOnceWithLock() {
        if (!properties.isDistributedLockEnabled()) {
            return cleanupOnce();
        }
        String lockKey = normalizeLockKey(properties.getLockKey());
        String lockValue = UUID.randomUUID().toString().replace("-", "");
        if (!distributedLockService.tryLock(lockKey, lockValue,
                Duration.ofSeconds(normalizeLockTtlSeconds(properties.getLockTtlSeconds())))) {
            log.info("retention cleanup skipped, lock is held by another worker");
            return CleanupResult.builder().build();
        }
        try {
            return cleanupOnce();
        } finally {
            distributedLockService.release(lockKey, lockValue);
        }
    }

    public CleanupResult cleanupOnce() {
        long startMillis = System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();
        int batchSize = normalizeBatchSize(properties.getBatchSize());

        int expiredTasks = asyncTaskRecordMapper.deleteTerminalBefore(
                now.minusHours(normalizeRetentionHours(properties.getTaskRetentionHours())),
                batchSize);
        LocalDateTime uploadTaskExpireBefore =
                now.minusHours(normalizeRetentionHours(properties.getUploadTaskRetentionHours()));
        int uploadTasks = cleanupExpiredUploadingTasks(uploadTaskExpireBefore, batchSize)
                + fileUploadTaskMapper.deleteFinishedBefore(uploadTaskExpireBefore, batchSize);
        int deletedFiles = cleanupDeletedFiles(
                now.minusHours(normalizeRetentionHours(properties.getDeletedFileRetentionHours())),
                batchSize);
        int importStageRows = studentMapper.deleteImportStageBefore(
                now.minusHours(normalizeRetentionHours(properties.getImportStageRetentionHours())),
                batchSize);
        int importVersionRows = cleanupExpiredImportVersions(batchSize);

        CleanupResult result = CleanupResult.builder()
                .expiredTasks(expiredTasks)
                .uploadTasks(uploadTasks)
                .deletedFiles(deletedFiles)
                .importStageRows(importStageRows)
                .importVersionRows(importVersionRows)
                .build();
        log.info("retention cleanup finished, expiredTasks={}, uploadTasks={}, deletedFiles={}, importStageRows={}, importVersionRows={}, elapsedMs={}",
                result.getExpiredTasks(), result.getUploadTasks(), result.getDeletedFiles(),
                result.getImportStageRows(), result.getImportVersionRows(), System.currentTimeMillis() - startMillis);
        return result;
    }

    private int cleanupExpiredImportVersions(int batchSize) {
        if (!properties.isImportVersionCleanupEnabled()) {
            return 0;
        }
        int retainedHistoryVersions = Math.max(0, properties.getImportVersionRetainCount() - 1);
        // 版本清理是纯 DELETE（无应用侧列表查询），单条语句可承载更大批次；
        // 通用 normalizeBatchSize 的 1000 上限面向列表型清理，这里用独立上限，配合轮内循环收敛大批量堆积
        int versionBatchSize = Math.max(200, Math.min(properties.getBatchSize(), MAX_VERSION_CLEANUP_BATCH_SIZE));
        int totalDeleted = 0;
        for (int round = 0; round < MAX_VERSION_CLEANUP_ROUNDS_PER_RUN; round++) {
            int deleted = studentMapper.deleteExpiredStudentVersions(retainedHistoryVersions, versionBatchSize);
            totalDeleted += deleted;
            if (deleted < versionBatchSize) {
                break;
            }
        }
        return totalDeleted;
    }

    private int cleanupDeletedFiles(LocalDateTime updatedBefore, int batchSize) {
        List<FileRecord> deletedFiles = fileRecordMapper.listDeletedBefore(updatedBefore, batchSize);
        int deletedCount = 0;
        for (FileRecord fileRecord : deletedFiles) {
            fileObjectStorageService.deleteQuietly(fileRecord.getObjectKey());
            deletedCount += fileRecordMapper.deleteById(fileRecord.getId());
        }
        return deletedCount;
    }

    private int cleanupExpiredUploadingTasks(LocalDateTime createdBefore, int batchSize) {
        List<FileUploadTask> expiredTasks = fileUploadTaskMapper.listUploadingBefore(createdBefore, batchSize);
        if (expiredTasks == null || expiredTasks.isEmpty()) {
            return 0;
        }
        int deletedCount = 0;
        for (FileUploadTask task : expiredTasks) {
            cleanupUploadTaskObjects(task);
            deletedCount += fileUploadTaskMapper.deleteById(task.getId());
        }
        return deletedCount;
    }

    private void cleanupUploadTaskObjects(FileUploadTask task) {
        if (task == null) {
            return;
        }
        try {
            fileObjectStorageService.deleteQuietly(task.getObjectKey());
        } catch (RuntimeException ex) {
            recordCleanupFailure(task, task.getObjectKey(), ex);
            log.warn("cleanup expired upload object failed, uploadId={}, objectKey={}",
                    task.getUploadId(), task.getObjectKey(), ex);
        }
        String partObjectPrefix = task.getPartObjectPrefix();
        if (partObjectPrefix == null || partObjectPrefix.trim().isEmpty()) {
            return;
        }
        try {
            List<String> objectKeys = fileObjectStorageService.listObjectKeys(partObjectPrefix);
            fileObjectStorageService.deleteQuietly(objectKeys);
        } catch (RuntimeException ex) {
            recordCleanupFailure(task, partObjectPrefix, ex);
            log.warn("cleanup expired multipart parts failed, uploadId={}, partPrefix={}",
                    task.getUploadId(), partObjectPrefix, ex);
        }
    }

    private void recordCleanupFailure(FileUploadTask task, String objectKey, RuntimeException ex) {
        compensationService.recordPending(
                "FILE_UPLOAD",
                task.getUploadId(),
                CompensationFailureType.CLEANUP_OBJECT_FAILED.name(),
                "objectKey=" + objectKey + ",error=" + safeErrorMessage(ex));
    }

    private String safeErrorMessage(RuntimeException ex) {
        if (ex == null || ex.getMessage() == null) {
            return "unknown";
        }
        String message = ex.getMessage().replace('\n', ' ').replace('\r', ' ');
        return message.length() > 512 ? message.substring(0, 512) : message;
    }

    private int normalizeRetentionHours(int retentionHours) {
        return Math.max(1, retentionHours);
    }

    private int normalizeBatchSize(int batchSize) {
        if (batchSize <= 0) {
            return 100;
        }
        return Math.min(batchSize, 1000);
    }

    private String normalizeLockKey(String lockKey) {
        if (lockKey == null || lockKey.trim().isEmpty()) {
            return "cleanup:retention:lock";
        }
        return lockKey.trim();
    }

    private int normalizeLockTtlSeconds(int ttlSeconds) {
        return Math.max(60, ttlSeconds);
    }
}
