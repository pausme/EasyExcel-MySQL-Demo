package com.huang.demo.common.compensation.service.impl;

import com.huang.demo.common.compensation.config.CompensationProperties;
import com.huang.demo.common.compensation.domain.entity.CompensationRecord;
import com.huang.demo.common.compensation.repository.CompensationRecordMapper;
import com.huang.demo.common.compensation.service.CompensationHandler;
import com.huang.demo.common.lock.DistributedLockService;
import com.huang.demo.task.monitor.TaskMetricsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompensationAutoExecutorTest {

    @Test
    void executeDueCompensationsSkipsWhenLockIsHeld() {
        DistributedLockService lockService = mock(DistributedLockService.class);
        when(lockService.tryLock(eq("compensation:auto-execute:lock"), anyString(), any(Duration.class)))
                .thenReturn(false);
        CompensationRecordMapper mapper = mock(CompensationRecordMapper.class);
        CompensationAutoExecutor executor = newExecutor(mapper, lockService,
                Collections.singletonList(mock(CompensationHandler.class)));

        executor.executeDueCompensations();

        verify(mapper, never()).listDueForAutoExecute(any(), any(Integer.class));
    }

    @Test
    void executeBatchMarksHandledRecordSuccess() {
        CompensationRecord record = record(0, 3);
        CompensationRecordMapper mapper = mock(CompensationRecordMapper.class);
        when(mapper.listDueForAutoExecute(any(), eq(20))).thenReturn(Collections.singletonList(record));
        when(mapper.markRunning(eq("comp-1"), any())).thenReturn(1);
        CompensationHandler handler = mock(CompensationHandler.class);
        when(handler.supports(record)).thenReturn(true);
        CompensationAutoExecutor executor = newExecutor(
                mapper, mock(DistributedLockService.class), Collections.singletonList(handler));

        int handled = executor.executeBatch();

        assertEquals(1, handled);
        verify(handler).handle(record);
        verify(mapper).markSuccess(eq("comp-1"), any());
    }

    @Test
    void executeBatchMarksUnsupportedRecordTerminalFailed() {
        CompensationRecord record = record(0, 3);
        CompensationRecordMapper mapper = mock(CompensationRecordMapper.class);
        when(mapper.listDueForAutoExecute(any(), eq(20))).thenReturn(Collections.singletonList(record));
        when(mapper.markRunning(eq("comp-1"), any())).thenReturn(1);
        CompensationHandler handler = mock(CompensationHandler.class);
        when(handler.supports(record)).thenReturn(false);
        CompensationAutoExecutor executor = newExecutor(
                mapper, mock(DistributedLockService.class), Collections.singletonList(handler));

        executor.executeBatch();

        verify(mapper).markFailedTerminal(eq("comp-1"), eq("未找到自动补偿处理器，需人工处理"), any());
        verify(handler, never()).handle(record);
    }

    @Test
    void executeBatchSchedulesRetryWithBackoffWhenHandlerFails() {
        CompensationRecord record = record(1, 3);
        CompensationRecordMapper mapper = mock(CompensationRecordMapper.class);
        when(mapper.listDueForAutoExecute(any(), eq(20))).thenReturn(Collections.singletonList(record));
        when(mapper.markRunning(eq("comp-1"), any())).thenReturn(1);
        CompensationHandler handler = mock(CompensationHandler.class);
        when(handler.supports(record)).thenReturn(true);
        doThrow(new IllegalStateException("temporary failure")).when(handler).handle(record);
        CompensationAutoExecutor executor = newExecutor(
                mapper, mock(DistributedLockService.class), Collections.singletonList(handler));

        executor.executeBatch();

        ArgumentCaptor<LocalDateTime> nextRetryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).markFailed(eq("comp-1"), eq("temporary failure"), nextRetryAt.capture(), any());
        assertTrue(nextRetryAt.getValue().isAfter(LocalDateTime.now()));
    }

    @Test
    void executeBatchStopsRetryWhenMaxAttemptsReached() {
        CompensationRecord record = record(2, 3);
        CompensationRecordMapper mapper = mock(CompensationRecordMapper.class);
        when(mapper.listDueForAutoExecute(any(), eq(20))).thenReturn(Collections.singletonList(record));
        when(mapper.markRunning(eq("comp-1"), any())).thenReturn(1);
        CompensationHandler handler = mock(CompensationHandler.class);
        when(handler.supports(record)).thenReturn(true);
        doThrow(new IllegalStateException("still failing")).when(handler).handle(record);
        CompensationAutoExecutor executor = newExecutor(
                mapper, mock(DistributedLockService.class), Collections.singletonList(handler));

        executor.executeBatch();

        verify(mapper).markFailedTerminal(eq("comp-1"), eq("still failing"), any());
        verify(mapper, never()).markFailed(eq("comp-1"), anyString(), any(), any());
    }

    private CompensationAutoExecutor newExecutor(CompensationRecordMapper mapper,
                                                 DistributedLockService lockService,
                                                 java.util.List<CompensationHandler> handlers) {
        CompensationProperties properties = new CompensationProperties();
        properties.setRetryBackoffBaseSeconds(60);
        properties.setRetryBackoffMaxSeconds(3600);
        return new CompensationAutoExecutor(
                properties,
                mapper,
                lockService,
                handlers,
                mock(TaskMetricsService.class));
    }

    private CompensationRecord record(int retryCount, int maxRetryCount) {
        return CompensationRecord.builder()
                .compensationId("comp-1")
                .bizType("FILE")
                .bizId("file-1")
                .failureType("ORPHAN_OBJECT")
                .status("PENDING")
                .retryCount(retryCount)
                .maxRetryCount(maxRetryCount)
                .nextRetryAt(LocalDateTime.now().minusSeconds(1))
                .payload("objectKey=files/general/a.txt")
                .createdAt(LocalDateTime.now().minusMinutes(10))
                .updatedAt(LocalDateTime.now().minusMinutes(5))
                .build();
    }
}
