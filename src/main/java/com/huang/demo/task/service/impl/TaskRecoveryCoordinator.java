package com.huang.demo.task.service.impl;

import com.huang.demo.task.config.TaskCenterProperties;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.service.TaskCenterService;
import com.huang.demo.task.service.TaskRecoveryHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class TaskRecoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryCoordinator.class);

    private final TaskCenterService taskCenterService;
    private final TaskCenterProperties properties;
    private final Map<String, TaskRecoveryHandler> recoveryHandlerMap;

    public TaskRecoveryCoordinator(TaskCenterService taskCenterService,
                                   TaskCenterProperties properties,
                                   List<TaskRecoveryHandler> recoveryHandlers) {
        this.taskCenterService = taskCenterService;
        this.properties = properties;
        this.recoveryHandlerMap = buildRecoveryHandlerMap(recoveryHandlers);
    }

    @Scheduled(
            fixedDelayString = "${app.task.recovery-fixed-delay-millis:60000}",
            initialDelayString = "${app.task.recovery-initial-delay-millis:30000}")
    public void recoverTasks() {
        if (!properties.isRecoveryEnabled()) {
            return;
        }
        List<AsyncTaskRecord> tasks = taskCenterService.listRecoverableTasks(properties.getRecoveryBatchSize());
        for (AsyncTaskRecord task : tasks) {
            recoverOne(task);
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
}
