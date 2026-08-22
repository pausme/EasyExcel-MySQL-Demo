package com.huang.demo.common.compensation.service.impl;

import com.huang.demo.common.compensation.config.CompensationProperties;
import com.huang.demo.common.compensation.domain.entity.CompensationRecord;
import com.huang.demo.common.compensation.repository.CompensationRecordMapper;
import com.huang.demo.common.compensation.service.CompensationHandler;
import com.huang.demo.common.lock.DistributedLockService;
import com.huang.demo.task.monitor.TaskMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class CompensationAutoExecutor {

    private static final Logger log = LoggerFactory.getLogger(CompensationAutoExecutor.class);
    private static final int MAX_ERROR_LENGTH = 1024;

    private final CompensationProperties properties;
    private final CompensationRecordMapper recordMapper;
    private final DistributedLockService distributedLockService;
    private final List<CompensationHandler> handlers;
    private final TaskMetricsService taskMetricsService;

    public CompensationAutoExecutor(CompensationProperties properties,
                                    CompensationRecordMapper recordMapper,
                                    DistributedLockService distributedLockService,
                                    List<CompensationHandler> handlers,
                                    TaskMetricsService taskMetricsService) {
        this.properties = properties;
        this.recordMapper = recordMapper;
        this.distributedLockService = distributedLockService;
        this.handlers = handlers;
        this.taskMetricsService = taskMetricsService;
    }

    @Scheduled(
            initialDelayString = "${app.compensation.auto-execute-initial-delay-millis:60000}",
            fixedDelayString = "${app.compensation.auto-execute-fixed-delay-millis:60000}")
    public void executeDueCompensations() {
        if (!properties.isAutoExecuteEnabled()) {
            return;
        }
        String ownerToken = UUID.randomUUID().toString();
        Duration ttl = Duration.ofSeconds(Math.max(1L, properties.getAutoExecuteLockTtlSeconds()));
        if (!distributedLockService.tryLock(properties.getAutoExecuteLockKey(), ownerToken, ttl)) {
            log.info("compensation auto execution skipped, lock is held by another worker");
            return;
        }
        try {
            executeBatch();
        } finally {
            distributedLockService.release(properties.getAutoExecuteLockKey(), ownerToken);
        }
    }

    int executeBatch() {
        LocalDateTime now = LocalDateTime.now();
        List<CompensationRecord> records = recordMapper.listDueForAutoExecute(
                now, normalizeBatchSize(properties.getAutoExecuteBatchSize()));
        taskMetricsService.recordCompensationBacklog("auto_due", records.size());
        int handled = 0;
        for (CompensationRecord record : records) {
            if (record == null || !tryMarkRunning(record)) {
                if (record != null) {
                    recordOutcome("skipped", record);
                }
                continue;
            }
            handled++;
            executeOne(record);
        }
        if (handled > 0) {
            log.info("compensation auto execution finished, handled={}", handled);
        }
        return handled;
    }

    private boolean tryMarkRunning(CompensationRecord record) {
        int updated = recordMapper.markRunning(record.getCompensationId(), LocalDateTime.now());
        if (updated == 0) {
            log.info("compensation auto execution skipped, record already changed, compensationId={}",
                    record.getCompensationId());
            return false;
        }
        return true;
    }

    private void executeOne(CompensationRecord record) {
        CompensationHandler handler = resolveHandler(record);
        if (handler == null) {
            recordMapper.markFailedTerminal(
                    record.getCompensationId(),
                    "未找到自动补偿处理器，需人工处理",
                    LocalDateTime.now());
            recordOutcome("unsupported", record);
            log.warn("compensation auto execution unsupported, compensationId={}, bizType={}, failureType={}",
                    record.getCompensationId(), record.getBizType(), record.getFailureType());
            return;
        }
        try {
            handler.handle(record);
            recordMapper.markSuccess(record.getCompensationId(), LocalDateTime.now());
            recordOutcome("success", record);
            log.info("compensation auto execution succeeded, compensationId={}, bizType={}, failureType={}",
                    record.getCompensationId(), record.getBizType(), record.getFailureType());
        } catch (RuntimeException ex) {
            markFailed(record, ex);
            recordOutcome("failed", record);
            log.warn("compensation auto execution failed, compensationId={}, bizType={}, failureType={}",
                    record.getCompensationId(), record.getBizType(), record.getFailureType(), ex);
        }
    }

    private void markFailed(CompensationRecord record, RuntimeException ex) {
        LocalDateTime now = LocalDateTime.now();
        String error = safeErrorMessage(ex);
        if (isLastAttempt(record)) {
            recordMapper.markFailedTerminal(record.getCompensationId(), error, now);
            return;
        }
        recordMapper.markFailed(record.getCompensationId(), error, nextRetryAt(record, now), now);
    }

    private boolean isLastAttempt(CompensationRecord record) {
        int retryCount = record.getRetryCount() == null ? 0 : record.getRetryCount();
        int maxRetryCount = record.getMaxRetryCount() == null ? 0 : record.getMaxRetryCount();
        return retryCount + 1 >= maxRetryCount;
    }

    private LocalDateTime nextRetryAt(CompensationRecord record, LocalDateTime now) {
        int retryCount = record.getRetryCount() == null ? 0 : record.getRetryCount();
        long multiplier = 1L << Math.min(retryCount, 10);
        long delaySeconds = Math.max(1, properties.getRetryBackoffBaseSeconds()) * multiplier;
        delaySeconds = Math.min(delaySeconds, Math.max(1, properties.getRetryBackoffMaxSeconds()));
        return now.plusSeconds(delaySeconds);
    }

    private CompensationHandler resolveHandler(CompensationRecord record) {
        if (handlers == null || handlers.isEmpty()) {
            return null;
        }
        for (CompensationHandler handler : handlers) {
            if (handler.supports(record)) {
                return handler;
            }
        }
        return null;
    }

    private int normalizeBatchSize(int batchSize) {
        if (batchSize <= 0) {
            return 20;
        }
        return Math.min(batchSize, 200);
    }

    private String safeErrorMessage(RuntimeException ex) {
        String message = ex == null ? null : ex.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = ex == null ? "unknown" : ex.getClass().getSimpleName();
        }
        message = message.replace('\n', ' ').replace('\r', ' ');
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }

    private void recordOutcome(String outcome, CompensationRecord record) {
        taskMetricsService.recordCompensationAutoExecution(outcome, record.getBizType(), record.getFailureType());
    }

    public List<String> autoExecutableStatuses() {
        return Arrays.asList("PENDING", "FAILED");
    }
}
