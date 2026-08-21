package com.huang.demo.task.service.impl;

import com.huang.demo.common.compensation.service.CompensationService;
import com.huang.demo.common.lock.DistributedLockService;
import com.huang.demo.task.config.TaskCenterProperties;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.domain.model.AsyncTaskType;
import com.huang.demo.task.service.TaskCenterService;
import com.huang.demo.task.service.TaskRecoveryHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskRecoveryCoordinatorTest {

    private TaskCenterService taskCenterService;
    private TaskCenterProperties properties;
    private TaskRecoveryHandler exportRecoveryHandler;
    private DistributedLockService distributedLockService;
    private CompensationService compensationService;
    private TaskRecoveryCoordinator recoveryCoordinator;

    @BeforeEach
    void setUp() {
        taskCenterService = mock(TaskCenterService.class);
        properties = new TaskCenterProperties();
        exportRecoveryHandler = mock(TaskRecoveryHandler.class);
        distributedLockService = mock(DistributedLockService.class);
        compensationService = mock(CompensationService.class);
        when(exportRecoveryHandler.taskType()).thenReturn(AsyncTaskType.EXPORT.name());
        when(distributedLockService.tryLock(anyString(), anyString(), any())).thenReturn(true);
        recoveryCoordinator = new TaskRecoveryCoordinator(
                taskCenterService,
                properties,
                Collections.singletonList(exportRecoveryHandler),
                distributedLockService,
                compensationService);
    }

    @Test
    void recoveryDisabledDoesNotQueryTasks() {
        properties.setRecoveryEnabled(false);

        recoveryCoordinator.recoverTasks();

        verify(taskCenterService, never()).listRecoverableTasks(anyInt());
    }

    @Test
    void noHandlerSkipsTaskAndDoesNotClaim() {
        AsyncTaskRecord task = buildTask("unknown-task-type");
        when(taskCenterService.listRecoverableTasks(properties.getRecoveryBatchSize()))
                .thenReturn(Collections.singletonList(task));

        recoveryCoordinator.recoverTasks();

        verify(taskCenterService, never()).claimRecoverableTask(task.getTaskId());
        verify(exportRecoveryHandler, never()).recover(task);
    }

    @Test
    void claimFailedSkipsRecoveryHandler() {
        AsyncTaskRecord task = buildTask(AsyncTaskType.EXPORT.name());
        when(taskCenterService.listRecoverableTasks(properties.getRecoveryBatchSize()))
                .thenReturn(Collections.singletonList(task));
        when(taskCenterService.claimRecoverableTask(task.getTaskId())).thenReturn(false);

        recoveryCoordinator.recoverTasks();

        verify(exportRecoveryHandler, never()).recover(task);
    }

    @Test
    void claimSuccessSubmitsRecoveryHandler() {
        AsyncTaskRecord task = buildTask(AsyncTaskType.EXPORT.name());
        when(taskCenterService.listRecoverableTasks(properties.getRecoveryBatchSize()))
                .thenReturn(Collections.singletonList(task));
        when(taskCenterService.claimRecoverableTask(task.getTaskId())).thenReturn(true);
        when(taskCenterService.currentWorkerId()).thenReturn("worker-1");

        recoveryCoordinator.recoverTasks();

        verify(exportRecoveryHandler).recover(task);
    }

    @Test
    void handlerFailureMarksTaskFailed() {
        AsyncTaskRecord task = buildTask(AsyncTaskType.EXPORT.name());
        when(taskCenterService.listRecoverableTasks(properties.getRecoveryBatchSize()))
                .thenReturn(Collections.singletonList(task));
        when(taskCenterService.claimRecoverableTask(task.getTaskId())).thenReturn(true);
        doThrow(new IllegalStateException("executor rejected"))
                .when(exportRecoveryHandler).recover(task);

        recoveryCoordinator.recoverTasks();

        verify(taskCenterService).markFailed(task.getTaskId(), "任务恢复投递失败");
    }

    @Test
    void handlerFailureCreatesCompensationRecord() {
        AsyncTaskRecord task = buildTask(AsyncTaskType.EXPORT.name());
        when(taskCenterService.listRecoverableTasks(properties.getRecoveryBatchSize()))
                .thenReturn(Collections.singletonList(task));
        when(taskCenterService.claimRecoverableTask(task.getTaskId())).thenReturn(true);
        doThrow(new IllegalStateException("executor rejected"))
                .when(exportRecoveryHandler).recover(task);

        recoveryCoordinator.recoverTasks();

        verify(compensationService).recordPending(
                "ASYNC_TASK",
                task.getTaskId(),
                "RECOVERY_SUBMIT_FAILED",
                "taskType=EXPORT,workerId=null");
    }

    @Test
    void recoverySkipsWhenLockIsHeld() {
        when(distributedLockService.tryLock(anyString(), anyString(), any())).thenReturn(false);

        recoveryCoordinator.recoverTasks();

        verify(taskCenterService, never()).listRecoverableTasks(anyInt());
        verify(exportRecoveryHandler, never()).recover(any());
    }

    private AsyncTaskRecord buildTask(String taskType) {
        return AsyncTaskRecord.builder()
                .taskId("task-1")
                .ownerId("user-1")
                .taskType(taskType)
                .taskName("测试任务")
                .build();
    }
}
