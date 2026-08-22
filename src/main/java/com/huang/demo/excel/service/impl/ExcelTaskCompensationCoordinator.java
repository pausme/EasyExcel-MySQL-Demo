package com.huang.demo.excel.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.common.compensation.domain.model.CompensationFailureType;
import com.huang.demo.common.compensation.service.CompensationService;
import com.huang.demo.common.lock.DistributedLockService;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.domain.model.StudentExportTaskResult;
import com.huang.demo.excel.domain.model.StudentImportTaskPayload;
import com.huang.demo.excel.domain.model.StudentImportTaskResult;
import com.huang.demo.excel.service.MinioObjectStorageService;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.domain.model.AsyncTaskStatus;
import com.huang.demo.task.domain.model.AsyncTaskType;
import com.huang.demo.task.repository.AsyncTaskRecordMapper;
import com.huang.demo.task.service.TaskCenterService;
import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class ExcelTaskCompensationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ExcelTaskCompensationCoordinator.class);

    private final AsyncTaskRecordMapper taskRecordMapper;
    private final TaskCenterService taskCenterService;
    private final MinioObjectStorageService minioObjectStorageService;
    private final CompensationService compensationService;
    private final DistributedLockService distributedLockService;
    private final ObjectMapper objectMapper;
    private final ExcelDemoProperties properties;

    public ExcelTaskCompensationCoordinator(AsyncTaskRecordMapper taskRecordMapper,
                                            TaskCenterService taskCenterService,
                                            MinioObjectStorageService minioObjectStorageService,
                                            CompensationService compensationService,
                                            DistributedLockService distributedLockService,
                                            ObjectMapper objectMapper,
                                            ExcelDemoProperties properties) {
        this.taskRecordMapper = taskRecordMapper;
        this.taskCenterService = taskCenterService;
        this.minioObjectStorageService = minioObjectStorageService;
        this.compensationService = compensationService;
        this.distributedLockService = distributedLockService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${app.excel.task-compensation-fixed-delay-millis:3600000}",
            initialDelayString = "${app.excel.task-compensation-initial-delay-millis:600000}")
    public void scheduledCompensationScan() {
        if (!properties.isTaskCompensationEnabled()) {
            return;
        }
        compensateOnceWithLock();
    }

    public long compensateOnceWithLock() {
        String lockKey = normalizeLockKey(properties.getTaskCompensationLockKey());
        String ownerToken = UUID.randomUUID().toString().replace("-", "");
        if (!distributedLockService.tryLock(lockKey, ownerToken,
                Duration.ofSeconds(normalizeLockTtlSeconds(properties.getTaskCompensationLockTtlSeconds())))) {
            log.info("excel task compensation skipped, lock is held by another worker");
            return 0L;
        }
        try {
            return compensateOnce();
        } finally {
            distributedLockService.release(lockKey, ownerToken);
        }
    }

    public long compensateOnce() {
        int batchSize = normalizeBatchSize(properties.getTaskCompensationBatchSize());
        long handled = scanExportSuccessTasks(batchSize);
        handled += scanImportFailedTasks(batchSize);
        if (handled > 0) {
            log.info("excel task compensation scan finished, handled={}", handled);
        }
        return handled;
    }

    private long scanExportSuccessTasks(int batchSize) {
        long lastId = 0L;
        long handled = 0L;
        while (true) {
            List<AsyncTaskRecord> tasks = safeTasks(taskRecordMapper.listByTypeAndStatusAfterId(
                    AsyncTaskType.EXPORT.name(), AsyncTaskStatus.SUCCESS.name(), lastId, batchSize));
            if (tasks.isEmpty()) {
                return handled;
            }
            for (AsyncTaskRecord task : tasks) {
                lastId = maxId(lastId, task);
                StudentExportTaskResult result = readExportResult(task.getResultPayload());
                if (!hasText(result.getObjectKey()) || objectExists(result.getObjectKey())) {
                    continue;
                }
                recordMissingObject(task.getTaskId(), "EXPORT", result.getObjectKey(),
                        "导出任务成功但结果对象不存在");
                try {
                    taskCenterService.markExpired(task.getTaskId(),
                            "导出文件不存在或已过期",
                            "导出文件已被清理，可重试任务或重新提交导出");
                } catch (RuntimeException ex) {
                    log.warn("mark missing export task expired failed, taskId={}", task.getTaskId(), ex);
                }
                handled++;
            }
            if (tasks.size() < batchSize) {
                return handled;
            }
        }
    }

    private long scanImportFailedTasks(int batchSize) {
        long lastId = 0L;
        long handled = 0L;
        while (true) {
            List<AsyncTaskRecord> tasks = safeTasks(taskRecordMapper.listByTypeAndStatusAfterId(
                    AsyncTaskType.IMPORT.name(), AsyncTaskStatus.FAILED.name(), lastId, batchSize));
            if (tasks.isEmpty()) {
                return handled;
            }
            for (AsyncTaskRecord task : tasks) {
                lastId = maxId(lastId, task);
                StudentImportTaskResult result = readImportResult(task.getResultPayload());
                if (hasText(result.getErrorObjectKey()) && !objectExists(result.getErrorObjectKey())) {
                    recordMissingObject(task.getTaskId(), "IMPORT", result.getErrorObjectKey(),
                            "导入错误明细任务失败但结果对象不存在");
                    handled++;
                }
                if (isDependencyFailure(task) && hasText(task.getRequestPayload())) {
                    StudentImportTaskPayload payload = readImportPayload(task.getRequestPayload());
                    if (hasText(payload.getSourceObjectKey()) && !objectExists(payload.getSourceObjectKey())) {
                        recordMissingObject(task.getTaskId(), "IMPORT", payload.getSourceObjectKey(),
                                "导入任务依赖源文件不存在");
                        handled++;
                    }
                }
            }
            if (tasks.size() < batchSize) {
                return handled;
            }
        }
    }

    private void recordMissingObject(String taskId, String bizType, String objectKey, String reason) {
        compensationService.recordPending(
                bizType,
                taskId,
                CompensationFailureType.OBJECT_MISSING.name(),
                "objectKey=" + objectKey + ",reason=" + reason);
    }

    private boolean objectExists(String objectKey) {
        try {
            minioObjectStorageService.ensureObjectExists(objectKey);
            return true;
        } catch (RuntimeException ex) {
            return !isMissingObjectException(ex);
        }
    }

    private boolean isMissingObjectException(RuntimeException ex) {
        if (ex == null) {
            return false;
        }
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ErrorResponseException) {
                ErrorResponseException responseException = (ErrorResponseException) current;
                String code = responseException.errorResponse() == null
                        ? null : responseException.errorResponse().code();
                return "NoSuchKey".equals(code)
                        || "NoSuchObject".equals(code)
                        || "NoSuchBucket".equals(code);
            }
            current = current.getCause();
        }
        return ex.getMessage() != null && ex.getMessage().contains("MinIO 文件不存在或已过期");
    }

    private boolean isDependencyFailure(AsyncTaskRecord task) {
        return "DEPENDENCY_ERROR".equals(task.getFailureType())
                || (task.getErrorMessage() != null && task.getErrorMessage().contains("源文件"));
    }

    private StudentExportTaskResult readExportResult(String payload) {
        if (!hasText(payload)) {
            return new StudentExportTaskResult();
        }
        try {
            return objectMapper.readValue(payload, StudentExportTaskResult.class);
        } catch (IOException ex) {
            log.warn("parse export task result for compensation failed", ex);
            return new StudentExportTaskResult();
        }
    }

    private StudentImportTaskResult readImportResult(String payload) {
        if (!hasText(payload)) {
            return new StudentImportTaskResult();
        }
        try {
            return objectMapper.readValue(payload, StudentImportTaskResult.class);
        } catch (IOException ex) {
            log.warn("parse import task result for compensation failed", ex);
            return new StudentImportTaskResult();
        }
    }

    private StudentImportTaskPayload readImportPayload(String payload) {
        try {
            return objectMapper.readValue(payload, StudentImportTaskPayload.class);
        } catch (IOException ex) {
            log.warn("parse import task payload for compensation failed", ex);
            return new StudentImportTaskPayload();
        }
    }

    private List<AsyncTaskRecord> safeTasks(List<AsyncTaskRecord> tasks) {
        return tasks == null ? Collections.<AsyncTaskRecord>emptyList() : tasks;
    }

    private long maxId(long current, AsyncTaskRecord task) {
        return task == null || task.getId() == null ? current : Math.max(current, task.getId());
    }

    private int normalizeBatchSize(int batchSize) {
        return batchSize <= 0 ? 100 : Math.min(batchSize, 1000);
    }

    private int normalizeLockTtlSeconds(int ttlSeconds) {
        return Math.max(60, ttlSeconds);
    }

    private String normalizeLockKey(String lockKey) {
        return hasText(lockKey) ? lockKey.trim() : "task:compensation:lock";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
