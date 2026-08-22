package com.huang.demo.common.idempotency.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.common.idempotency.domain.entity.IdempotencyRecord;
import com.huang.demo.common.idempotency.domain.model.IdempotencyStatus;
import com.huang.demo.common.idempotency.repository.IdempotencyRecordMapper;
import com.huang.demo.excel.api.dto.ExportTaskResponse;
import com.huang.demo.excel.domain.model.ExportTaskStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

class IdempotencyServiceImplTest {

    @Test
    void executeWithoutKeyBypassesIdempotencyRecord() throws Exception {
        IdempotencyRecordMapper mapper = mock(IdempotencyRecordMapper.class);
        IdempotencyServiceImpl service = new IdempotencyServiceImpl(mapper, new ObjectMapper().findAndRegisterModules());

        ExportTaskResponse response = service.execute(
                "user-1", "EXPORT", null, "fingerprint", ExportTaskResponse.class,
                () -> ExportTaskResponse.builder().taskId("task-1").status(ExportTaskStatus.QUEUED).build());

        assertEquals("task-1", response.getTaskId());
        verify(mapper, never()).insert(any(IdempotencyRecord.class));
    }

    @Test
    void firstExecuteStoresSuccessResponse() throws Exception {
        IdempotencyRecordMapper mapper = mock(IdempotencyRecordMapper.class);
        when(mapper.findByKey("user-1", "EXPORT", "key-1")).thenReturn(Optional.empty());
        when(mapper.insert(any(IdempotencyRecord.class))).thenAnswer(invocation -> {
            IdempotencyRecord record = invocation.getArgument(0);
            record.setId(1L);
            return 1;
        });
        IdempotencyServiceImpl service = new IdempotencyServiceImpl(mapper, new ObjectMapper().findAndRegisterModules());

        ExportTaskResponse response = service.execute(
                "user-1", "EXPORT", "key-1", "fingerprint", ExportTaskResponse.class,
                () -> ExportTaskResponse.builder().taskId("task-1").status(ExportTaskStatus.QUEUED).build());

        assertEquals("task-1", response.getTaskId());
        verify(mapper).markSuccess(org.mockito.Mockito.eq(1L), org.mockito.Mockito.contains("task-1"), any());
    }

    @Test
    void successRecordReturnsCachedResponseWithoutExecutingAction() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        IdempotencyRecordMapper mapper = mock(IdempotencyRecordMapper.class);
        ExportTaskResponse cachedResponse = ExportTaskResponse.builder()
                .taskId("task-1")
                .status(ExportTaskStatus.QUEUED)
                .build();
        when(mapper.findByKey("user-1", "EXPORT", "key-1")).thenReturn(Optional.of(IdempotencyRecord.builder()
                .id(1L)
                .ownerId("user-1")
                .operation("EXPORT")
                .idempotencyKey("key-1")
                .requestFingerprint("fingerprint")
                .status(IdempotencyStatus.SUCCESS.name())
                .responsePayload(objectMapper.writeValueAsString(cachedResponse))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .expireAt(LocalDateTime.now().plusHours(1))
                .build()));
        IdempotencyServiceImpl service = new IdempotencyServiceImpl(mapper, objectMapper);
        AtomicInteger executionCount = new AtomicInteger();

        ExportTaskResponse response = service.execute(
                "user-1", "EXPORT", "key-1", "fingerprint", ExportTaskResponse.class,
                () -> {
                    executionCount.incrementAndGet();
                    return ExportTaskResponse.builder().taskId("task-2").build();
                });

        assertEquals("task-1", response.getTaskId());
        assertEquals(0, executionCount.get());
    }


    @Test
    void staleProcessingRecordIsReclaimedAndReExecuted() throws Exception {
        // A14 修复：僵死 PROCESSING（超 10 分钟未更新）被 CAS 回收后重新执行业务
        IdempotencyRecordMapper mapper = mock(IdempotencyRecordMapper.class);
        IdempotencyRecord stale = IdempotencyRecord.builder()
                .id(7L)
                .ownerId("user-1")
                .operation("EXPORT")
                .idempotencyKey("key-stale")
                .requestFingerprint("fingerprint")
                .status(IdempotencyStatus.PROCESSING.name())
                .updatedAt(LocalDateTime.now().minusMinutes(30))
                .build();
        when(mapper.findByKey("user-1", "EXPORT", "key-stale")).thenReturn(Optional.of(stale));
        when(mapper.tryReclaimStaleProcessing(eq(7L), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        IdempotencyServiceImpl service = new IdempotencyServiceImpl(mapper, new ObjectMapper().findAndRegisterModules());

        ExportTaskResponse response = service.execute(
                "user-1", "EXPORT", "key-stale", "fingerprint", ExportTaskResponse.class,
                () -> ExportTaskResponse.builder().taskId("task-reclaim").status(ExportTaskStatus.QUEUED).build());

        assertEquals("task-reclaim", response.getTaskId());
        verify(mapper).tryReclaimStaleProcessing(eq(7L), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(mapper).markSuccess(eq(7L), org.mockito.Mockito.contains("task-reclaim"), any());
    }

    @Test
    void freshProcessingRecordIsNotReclaimed() {
        // 未超时的 PROCESSING 仍返回"处理中"，不重执行
        IdempotencyRecordMapper mapper = mock(IdempotencyRecordMapper.class);
        IdempotencyRecord fresh = IdempotencyRecord.builder()
                .id(8L)
                .requestFingerprint("fingerprint")
                .status(IdempotencyStatus.PROCESSING.name())
                .updatedAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(mapper.findByKey("user-1", "EXPORT", "key-fresh")).thenReturn(Optional.of(fresh));
        IdempotencyServiceImpl service = new IdempotencyServiceImpl(mapper, new ObjectMapper().findAndRegisterModules());

        assertThrows(IllegalStateException.class, () -> service.execute(
                "user-1", "EXPORT", "key-fresh", "fingerprint", ExportTaskResponse.class,
                () -> ExportTaskResponse.builder().taskId("should-not-run").status(ExportTaskStatus.QUEUED).build()));
        verify(mapper, never()).tryReclaimStaleProcessing(anyLong(), any(), any());
    }

    @Test
    void sameKeyWithDifferentFingerprintIsRejected() {
        IdempotencyRecordMapper mapper = mock(IdempotencyRecordMapper.class);
        when(mapper.findByKey("user-1", "EXPORT", "key-1")).thenReturn(Optional.of(IdempotencyRecord.builder()
                .id(1L)
                .ownerId("user-1")
                .operation("EXPORT")
                .idempotencyKey("key-1")
                .requestFingerprint("fingerprint-a")
                .status(IdempotencyStatus.SUCCESS.name())
                .responsePayload("{}")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .expireAt(LocalDateTime.now().plusHours(1))
                .build()));
        IdempotencyServiceImpl service = new IdempotencyServiceImpl(mapper, new ObjectMapper().findAndRegisterModules());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.execute("user-1", "EXPORT", "key-1", "fingerprint-b",
                        ExportTaskResponse.class, () -> ExportTaskResponse.builder().taskId("task-2").build()));

        assertEquals("幂等键已用于不同请求，请更换 Idempotency-Key", exception.getMessage());
    }
}
