package com.huang.demo.common.compensation.service.impl;

import com.huang.demo.common.compensation.api.dto.CompensationActionResponse;
import com.huang.demo.common.compensation.api.dto.CompensationPageQueryRequest;
import com.huang.demo.common.compensation.api.dto.CompensationPageResponse;
import com.huang.demo.common.compensation.domain.entity.CompensationRecord;
import com.huang.demo.common.compensation.repository.CompensationRecordMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompensationManagementServiceImplTest {

    @Test
    void pageNormalizesFiltersAndDelegatesToMapper() {
        CompensationRecordMapper mapper = mock(CompensationRecordMapper.class);
        when(mapper.countPage(eq("FILE"), eq("file-1"), eq("OBJECT_MISSING"),
                anyList(), any(), any())).thenReturn(1L);
        when(mapper.listPage(eq("FILE"), eq("file-1"), eq("OBJECT_MISSING"),
                anyList(), any(), any(), eq(20), eq(20)))
                .thenReturn(Collections.singletonList(CompensationRecord.builder()
                        .compensationId("comp-1")
                        .bizType("FILE")
                        .bizId("file-1")
                        .failureType("OBJECT_MISSING")
                        .status("FAILED")
                        .retryCount(1)
                        .maxRetryCount(3)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build()));
        CompensationManagementServiceImpl service = new CompensationManagementServiceImpl(mapper);
        CompensationPageQueryRequest request = new CompensationPageQueryRequest();
        request.setPageNo(2);
        request.setPageSize(20);
        request.setBizType(" FILE ");
        request.setBizId(" file-1 ");
        request.setFailureType(" OBJECT_MISSING ");
        request.setStatus("failed");
        request.setStatuses(Arrays.asList("pending", "FAILED"));

        CompensationPageResponse response = service.page(request);

        assertEquals(1L, response.getTotal());
        assertEquals(2, response.getPageNo());
        assertEquals(20, response.getPageSize());
        assertEquals("comp-1", response.getRecords().get(0).getCompensationId());
        verify(mapper).listPage(eq("FILE"), eq("file-1"), eq("OBJECT_MISSING"),
                eq(Arrays.asList("FAILED", "PENDING")), any(), any(), eq(20), eq(20));
    }

    @Test
    void retryMarksCompensationPendingForImmediateReplay() {
        CompensationRecordMapper mapper = mock(CompensationRecordMapper.class);
        when(mapper.markPendingForRetry(eq("comp-1"), any(), any())).thenReturn(1);
        CompensationManagementServiceImpl service = new CompensationManagementServiceImpl(mapper);

        CompensationActionResponse response = service.retry(" comp-1 ");

        assertEquals("comp-1", response.getCompensationId());
        assertEquals("PENDING", response.getStatus());
        assertTrue(response.isUpdated());
        verify(mapper).markPendingForRetry(eq("comp-1"), any(), any());
    }

    @Test
    void pageRejectsInvalidStatus() {
        CompensationManagementServiceImpl service = new CompensationManagementServiceImpl(
                mock(CompensationRecordMapper.class));
        CompensationPageQueryRequest request = new CompensationPageQueryRequest();
        request.setStatus("BAD");

        assertThrows(IllegalArgumentException.class, () -> service.page(request));
    }
}
