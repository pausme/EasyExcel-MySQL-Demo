package com.huang.demo.common.compensation.service.impl;

import com.huang.demo.common.compensation.domain.entity.CompensationRecord;
import com.huang.demo.common.compensation.repository.CompensationRecordMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompensationServiceImplTest {

    @Test
    void recordPendingCreatesNewActiveRecord() {
        CompensationRecordMapper mapper = mock(CompensationRecordMapper.class);
        when(mapper.findActive("FILE", "file-1", "OBJECT_MISSING"))
                .thenReturn(Optional.empty());
        CompensationServiceImpl service = new CompensationServiceImpl(mapper);

        CompensationRecord record = service.recordPending(
                "FILE", "file-1", "OBJECT_MISSING", "objectKey=files/a.txt");

        assertNotNull(record);
        assertEquals("FILE", record.getBizType());
        assertEquals("PENDING", record.getStatus());
        assertEquals(0, record.getRetryCount().intValue());
        verify(mapper).insert(any(CompensationRecord.class));
    }

    @Test
    void recordPendingReusesExistingActiveRecord() {
        CompensationRecord existing = CompensationRecord.builder()
                .compensationId("comp-1")
                .status("FAILED")
                .build();
        CompensationRecordMapper mapper = mock(CompensationRecordMapper.class);
        when(mapper.findActive("FILE", "file-1", "OBJECT_MISSING"))
                .thenReturn(Optional.of(existing));
        CompensationServiceImpl service = new CompensationServiceImpl(mapper);

        CompensationRecord record = service.recordPending(
                "FILE", "file-1", "OBJECT_MISSING", "objectKey=files/a.txt");

        assertEquals("comp-1", record.getCompensationId());
        verify(mapper, org.mockito.Mockito.never()).insert(any(CompensationRecord.class));
    }

    @Test
    void stateChangesDelegateToMapper() {
        CompensationRecordMapper mapper = mock(CompensationRecordMapper.class);
        CompensationServiceImpl service = new CompensationServiceImpl(mapper);

        service.markRunning("comp-1");
        service.markSuccess("comp-1");
        service.markFailed("comp-1", "temporary failure");

        verify(mapper).markRunning(anyString(), any());
        verify(mapper).markSuccess(anyString(), any());
        verify(mapper).markFailed(anyString(), anyString(), any(), any());
    }

    @Test
    void persistenceFailureDoesNotBlockMainFlow() {
        CompensationRecordMapper mapper = mock(CompensationRecordMapper.class);
        when(mapper.findActive("FILE", "file-1", "OBJECT_MISSING"))
                .thenThrow(new IllegalStateException("database unavailable"));
        CompensationServiceImpl service = new CompensationServiceImpl(mapper);

        CompensationRecord record = service.recordPending(
                "FILE", "file-1", "OBJECT_MISSING", "objectKey=files/a.txt");

        org.junit.jupiter.api.Assertions.assertNull(record);
    }

    @Test
    void stateUpdateFailureDoesNotBlockMainFlow() {
        CompensationRecordMapper mapper = mock(CompensationRecordMapper.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(mapper).markSuccess(anyString(), any());
        CompensationServiceImpl service = new CompensationServiceImpl(mapper);

        service.markSuccess("comp-1");

        verify(mapper).markSuccess(anyString(), any());
    }
}
