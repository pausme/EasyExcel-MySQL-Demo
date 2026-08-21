package com.huang.demo.task.service.impl;

import com.huang.demo.common.compensation.domain.model.CompensationFailureType;
import com.huang.demo.common.compensation.service.CompensationService;
import com.huang.demo.common.lock.DistributedLockService;
import com.huang.demo.task.config.TaskCenterProperties;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.service.TaskCenterService;
import com.huang.demo.task.service.TaskRecoveryHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class TaskRecoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryCoordinator.class);

    private final TaskCenterService taskCenterService;
    private final TaskCenterProperties properties;
    private final Map<String, TaskRecoveryHandler> recoveryHandlerMap;
    private final DistributedLockService distributedLockService;
    private final CompensationService compensationService;

    @Autowired
    public TaskRecoveryCoordinator(TaskCenterService taskCenterService,
                                   TaskCenterProperties properties,
                                   List<TaskRecoveryHandler> recoveryHandlers,
                                   DistributedLockService distributedLockService,
                                   CompensationService compensationService) {
        this.taskCenterService = taskCenterService;
        this.properties = properties;
        this.recoveryHandlerMap = buildRecoveryHandlerMap(recoveryHandlers);
        this.distributedLockService = distributedLockService;
        this.compensationService = compensationService;
    }

    public TaskRecoveryCoordinator(TaskCenterService taskCenterService,
                                   TaskCenterProperties properties,
                                   List<TaskRecoveryHandler> recoveryHandlers,
                                   DistributedLockService distributedLockService) {
        this(taskCenterService, properties, recoveryHandlers, distributedLockService,
                CompensationService.noop());
    }

    @Scheduled(
            fixedDelayString = "${app.task.recovery-fixed-delay-millis:60000}",
            initialDelayString = "${app.task.recovery-initial-delay-millis:30000}")
    public void recoverTasks() {
        if (!properties.isRecoveryEnabled()) {
            return;
        }
        String lockKey = normalizeLockKey(properties.getRecoveryLockKey());
        String lockValue = UUID.randomUUID().toString().replace("-", "");
        if (!distributedLockService.tryLock(lockKey, lockValue,
                Duration.ofSeconds(normalizeLockTtlSeconds(properties.getRecoveryLockTtlSeconds())))) {
            log.info("async task recovery skipped, lock is held by another worker");
            return;
        }
        try {
            List<AsyncTaskRecord> tasks = taskCenterService.listRecoverableTasks(properties.getRecoveryBatchSize());
            for (AsyncTaskRecord task : tasks) {
                recoverOne(task);
            }
        } finally {
            distributedLockService.release(lockKey, lockValue);
        }
    }

    private void recoverOne(AsyncTaskRecord task) {
        TaskRecoveryHandler recoveryHandler = recoveryHandlerMap.get(task.getTaskType());
        if (recoveryHandler == null) {
            log.warn("async task recovery skipped, no handler, taskId={}, taskType={}",
                    task.getTaskId(), task.getTaskType());
            return;
        }
        if (!taskCenterService.claimRecoverableTask(task.getTaskId())) {
            return;
        }
        try {
            recoveryHandler.recover(task);
            log.info("async task recovery submitted, taskId={}, taskType={}, workerId={}",
                    task.getTaskId(), task.getTaskType(), taskCenterService.currentWorkerId());
        } catch (RuntimeException ex) {
            taskCenterService.markFailed(task.getTaskId(), "任务恢复投递失败");
            compensationService.recordPending(
                    "ASYNC_TASK",
                    task.getTaskId(),
                    CompensationFailureType.RECOVERY_SUBMIT_FAILED.name(),
                    "taskType=" + task.getTaskType() + ",workerId=" + taskCenterService.currentWorkerId());
            log.error("async task recovery failed, taskId={}, taskType={}",
                    task.getTaskId(), task.getTaskType(), ex);
        }
    }

    private Map<String, TaskRecoveryHandler> buildRecoveryHandlerMap(List<TaskRecoveryHandler> recoveryHandlers) {
        Map<String, TaskRecoveryHandler> result = new HashMap<String, TaskRecoveryHandler>();
        if (recoveryHandlers == null) {
            return result;
        }
        for (TaskRecoveryHandler recoveryHandler : recoveryHandlers) {
            result.put(recoveryHandler.taskType(), recoveryHandler);
        }
        return result;
    }

    private String normalizeLockKey(String lockKey) {
        if (lockKey == null || lockKey.trim().isEmpty()) {
            return "task:recovery:lock";
        }
        return lockKey.trim();
    }

    private int normalizeLockTtlSeconds(int ttlSeconds) {
        return Math.max(60, ttlSeconds);
    }
}
