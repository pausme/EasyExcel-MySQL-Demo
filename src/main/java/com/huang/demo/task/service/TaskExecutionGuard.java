package com.huang.demo.task.service;

import com.huang.demo.common.lock.DistributedLockService;
import com.huang.demo.task.config.TaskCenterProperties;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Prevents the same asynchronous task from being executed by multiple workers.
 */
@Component
public class TaskExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionGuard.class);

    private final TaskCenterService taskCenterService;
    private final DistributedLockService distributedLockService;
    private final TaskCenterProperties properties;
    private final boolean legacyMode;

    @Autowired
    public TaskExecutionGuard(TaskCenterService taskCenterService,
                              DistributedLockService distributedLockService,
                              TaskCenterProperties properties) {
        this.taskCenterService = taskCenterService;
        this.distributedLockService = distributedLockService;
        this.properties = properties;
        this.legacyMode = false;
    }

    /**
     * Compatibility constructor for direct unit-test construction of the Excel services.
     */
    public TaskExecutionGuard(TaskCenterService taskCenterService) {
        this.taskCenterService = taskCenterService;
        this.distributedLockService = null;
        this.properties = null;
        this.legacyMode = true;
    }

    public Optional<TaskExecutionLease> tryStart(String taskId, String taskType, String workerId) {
        if (legacyMode) {
            AsyncTaskRecord task = taskCenterService.markRunning(taskId, workerId);
            return task == null ? Optional.<TaskExecutionLease>empty()
                    : Optional.of(TaskExecutionLease.legacy(task));
        }

        String lockKey = buildLockKey(taskType, taskId);
        String ownerToken = UUID.randomUUID().toString().replace("-", "");
        Duration ttl = Duration.ofSeconds(normalizeTtlSeconds(properties.getExecutionLockTtlSeconds()));
        if (!distributedLockService.tryLock(lockKey, ownerToken, ttl)) {
            log.info("async task execution skipped, lock is held, taskId={}, taskType={}",
                    taskId, taskType);
            return Optional.empty();
        }
        try {
            Optional<AsyncTaskRecord> task = taskCenterService.claimRunning(taskId, workerId);
            if (!task.isPresent()) {
                distributedLockService.release(lockKey, ownerToken);
                log.info("async task execution skipped, task was already claimed or terminal, taskId={}, taskType={}",
                        taskId, taskType);
                return Optional.empty();
            }
            return Optional.of(new TaskExecutionLease(
                    task.get(), distributedLockService, lockKey, ownerToken));
        } catch (RuntimeException ex) {
            distributedLockService.release(lockKey, ownerToken);
            log.warn("async task execution claim failed, taskId={}, taskType={}", taskId, taskType, ex);
            return Optional.empty();
        }
    }

    private String buildLockKey(String taskType, String taskId) {
        String prefix = properties.getExecutionLockKeyPrefix();
        if (prefix == null || prefix.trim().isEmpty()) {
            prefix = "task:execution:";
        }
        String normalizedType = taskType == null || taskType.trim().isEmpty()
                ? "UNKNOWN" : taskType.trim().toUpperCase();
        return prefix.trim() + normalizedType + ":" + taskId.trim();
    }

    private int normalizeTtlSeconds(int ttlSeconds) {
        return Math.max(60, ttlSeconds);
    }

    public static final class TaskExecutionLease implements AutoCloseable {

        private final AsyncTaskRecord task;
        private final DistributedLockService distributedLockService;
        private final String lockKey;
        private final String ownerToken;

        private TaskExecutionLease(AsyncTaskRecord task,
                                   DistributedLockService distributedLockService,
                                   String lockKey,
                                   String ownerToken) {
            this.task = task;
            this.distributedLockService = distributedLockService;
            this.lockKey = lockKey;
            this.ownerToken = ownerToken;
        }

        private static TaskExecutionLease legacy(AsyncTaskRecord task) {
            return new TaskExecutionLease(task, null, null, null);
        }

        public AsyncTaskRecord getTask() {
            return task;
        }

        @Override
        public void close() {
            if (distributedLockService != null) {
                distributedLockService.release(lockKey, ownerToken);
            }
        }
    }
}
