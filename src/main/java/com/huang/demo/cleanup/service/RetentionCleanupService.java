package com.huang.demo.cleanup.service;

import com.huang.demo.cleanup.config.CleanupProperties;
import com.huang.demo.cleanup.domain.CleanupResult;
import com.huang.demo.excel.repository.StudentMapper;
import com.huang.demo.file.domain.entity.FileRecord;
import com.huang.demo.file.repository.FileRecordMapper;
import com.huang.demo.file.repository.FileUploadTaskMapper;
import com.huang.demo.file.service.FileObjectStorageService;
import com.huang.demo.task.repository.AsyncTaskRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class RetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

    private final CleanupProperties properties;
    private final AsyncTaskRecordMapper asyncTaskRecordMapper;
    private final FileUploadTaskMapper fileUploadTaskMapper;
    private final FileRecordMapper fileRecordMapper;
    private final StudentMapper studentMapper;
    private final FileObjectStorageService fileObjectStorageService;
    private final StringRedisTemplate stringRedisTemplate;

    public RetentionCleanupService(CleanupProperties properties,
                                   AsyncTaskRecordMapper asyncTaskRecordMapper,
                                   FileUploadTaskMapper fileUploadTaskMapper,
                                   FileRecordMapper fileRecordMapper,
                                   StudentMapper studentMapper,
                                   FileObjectStorageService fileObjectStorageService,
                                   StringRedisTemplate stringRedisTemplate) {
        this.properties = properties;
        this.asyncTaskRecordMapper = asyncTaskRecordMapper;
        this.fileUploadTaskMapper = fileUploadTaskMapper;
        this.fileRecordMapper = fileRecordMapper;
        this.studentMapper = studentMapper;
        this.fileObjectStorageService = fileObjectStorageService;
        this.stringRedisTemplate = stringRedisTemplate;
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
        if (!tryAcquireLock(lockKey, lockValue)) {
            log.info("retention cleanup skipped, lock is held by another worker");
            return CleanupResult.builder().build();
        }
        try {
            return cleanupOnce();
        } finally {
            releaseLockQuietly(lockKey, lockValue);
        }
    }

    public CleanupResult cleanupOnce() {
        long startMillis = System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();
        int batchSize = normalizeBatchSize(properties.getBatchSize());

        int expiredTasks = asyncTaskRecordMapper.deleteTerminalBefore(
                now.minusHours(normalizeRetentionHours(properties.getTaskRetentionHours())),
                batchSize);
        int uploadTasks = fileUploadTaskMapper.deleteFinishedBefore(
                now.minusHours(normalizeRetentionHours(properties.getUploadTaskRetentionHours())),
                batchSize);
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
        return studentMapper.deleteExpiredStudentVersions(retainedHistoryVersions, batchSize);
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

    private int normalizeRetentionHours(int retentionHours) {
        return Math.max(1, retentionHours);
    }

    private int normalizeBatchSize(int batchSize) {
        if (batchSize <= 0) {
            return 100;
        }
        return Math.min(batchSize, 1000);
    }

    private boolean tryAcquireLock(String lockKey, String lockValue) {
        try {
            Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(
                    lockKey, lockValue, Duration.ofSeconds(normalizeLockTtlSeconds(properties.getLockTtlSeconds())));
            return Boolean.TRUE.equals(locked);
        } catch (RuntimeException ex) {
            log.warn("acquire retention cleanup lock failed, lockKey={}", lockKey, ex);
            return false;
        }
    }

    private void releaseLockQuietly(String lockKey, String lockValue) {
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<Long>();
            script.setResultType(Long.class);
            script.setScriptText("if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end");
            stringRedisTemplate.execute(script, Collections.singletonList(lockKey), lockValue);
        } catch (RuntimeException ex) {
            log.warn("release retention cleanup lock failed, lockKey={}", lockKey, ex);
        }
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
