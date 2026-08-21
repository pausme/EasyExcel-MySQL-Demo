package com.huang.demo.task.service;

import com.huang.demo.common.lock.DistributedLockService;
import com.huang.demo.task.config.TaskCenterProperties;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskExecutionGuardTest {

    @Test
    void lockHeldSkipsExecutionWithoutClaimingTask() {
        TaskCenterService taskCenterService = mock(TaskCenterService.class);
        DistributedLockService lockService = mock(DistributedLockService.class);
        when(lockService.tryLock(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        TaskExecutionGuard guard = new TaskExecutionGuard(
                taskCenterService, lockService, new TaskCenterProperties());

        assertFalse(guard.tryStart("task-1", "EXPORT", "worker-1").isPresent());
        verify(taskCenterService, never()).claimRunning(anyString(), anyString());
    }

    @Test
    void successfulClaimReleasesTaskLockWhenLeaseCloses() {
        TaskCenterService taskCenterService = mock(TaskCenterService.class);
        DistributedLockService lockService = mock(DistributedLockService.class);
        when(lockService.tryLock(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(taskCenterService.claimRunning("task-1", "worker-1"))
                .thenReturn(Optional.of(AsyncTaskRecord.builder()
                        .taskId("task-1")
                        .taskType("EXPORT")
                        .status("RUNNING")
                        .build()));

        TaskExecutionGuard guard = new TaskExecutionGuard(
                taskCenterService, lockService, new TaskCenterProperties());

        Optional<TaskExecutionGuard.TaskExecutionLease> lease =
                guard.tryStart("task-1", "EXPORT", "worker-1");
        assertTrue(lease.isPresent());
        lease.get().close();

        verify(lockService).release(anyString(), anyString());
        verify(taskCenterService).claimRunning(eq("task-1"), eq("worker-1"));
    }

    @Test
    void failedClaimReleasesLockAndSkipsExecution() {
        TaskCenterService taskCenterService = mock(TaskCenterService.class);
        DistributedLockService lockService = mock(DistributedLockService.class);
        when(lockService.tryLock(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(taskCenterService.claimRunning("task-1", "IMPORT")).thenReturn(Optional.<AsyncTaskRecord>empty());

        TaskExecutionGuard guard = new TaskExecutionGuard(
                taskCenterService, lockService, new TaskCenterProperties());

        assertFalse(guard.tryStart("task-1", "IMPORT", "worker-1").isPresent());

        verify(lockService).release(anyString(), anyString());
    }
}
