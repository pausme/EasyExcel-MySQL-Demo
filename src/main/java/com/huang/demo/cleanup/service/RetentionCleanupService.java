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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class RetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

    private final CleanupProperties properties;
    private final AsyncTaskRecordMapper asyncTaskRecordMapper;
    private final FileUploadTaskMapper fileUploadTaskMapper;
    private final FileRecordMapper fileRecordMapper;
    private final StudentMapper studentMapper;
    private final FileObjectStorageService fileObjectStorageService;

    public RetentionCleanupService(CleanupProperties properties,
                                   AsyncTaskRecordMapper asyncTaskRecordMapper,
                                   FileUploadTaskMapper fileUploadTaskMapper,
                                   FileRecordMapper fileRecordMapper,
                                   StudentMapper studentMapper,
                                   FileObjectStorageService fileObjectStorageService) {
        this.properties = properties;
        this.asyncTaskRecordMapper = asyncTaskRecordMapper;
        this.fileUploadTaskMapper = fileUploadTaskMapper;
        this.fileRecordMapper = fileRecordMapper;
        this.studentMapper = studentMapper;
        this.fileObjectStorageService = fileObjectStorageService;
    }

    @Scheduled(
            initialDelayString = "${app.cleanup.initial-delay-millis:300000}",
            fixedDelayString = "${app.cleanup.fixed-delay-millis:3600000}"
    )
    public void scheduledCleanup() {
        if (!properties.isEnabled()) {
            return;
        }
        cleanupOnce();
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

        CleanupResult result = CleanupResult.builder()
                .expiredTasks(expiredTasks)
                .uploadTasks(uploadTasks)
                .deletedFiles(deletedFiles)
                .importStageRows(importStageRows)
                .build();
        log.info("retention cleanup finished, expiredTasks={}, uploadTasks={}, deletedFiles={}, importStageRows={}, elapsedMs={}",
                result.getExpiredTasks(), result.getUploadTasks(), result.getDeletedFiles(),
                result.getImportStageRows(), System.currentTimeMillis() - startMillis);
        return result;
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
}
