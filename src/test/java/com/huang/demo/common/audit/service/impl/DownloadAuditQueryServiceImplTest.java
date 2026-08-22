package com.huang.demo.common.audit.service.impl;

import com.huang.demo.common.audit.api.dto.DownloadAuditPageQueryRequest;
import com.huang.demo.common.audit.api.dto.DownloadAuditPageResponse;
import com.huang.demo.common.audit.domain.entity.DownloadAuditRecord;
import com.huang.demo.common.audit.repository.DownloadAuditRecordMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownloadAuditQueryServiceImplTest {

    @Test
    void nonAdminCanOnlyQueryCurrentOwner() {
        DownloadAuditRecordMapper mapper = mock(DownloadAuditRecordMapper.class);
        when(mapper.countPage(eq("user-1"), eq("FILE"), eq("file-1"), any(), any()))
                .thenReturn(1L);
        when(mapper.listPage(eq("user-1"), eq("FILE"), eq("file-1"), any(), any(), eq(0), eq(20)))
                .thenReturn(Collections.singletonList(DownloadAuditRecord.builder()
                        .auditId("audit-1")
                        .ownerId("user-1")
                        .resourceType("FILE")
                        .resourceId("file-1")
                        .createdAt(LocalDateTime.now())
                        .build()));
        DownloadAuditQueryServiceImpl service = new DownloadAuditQueryServiceImpl(mapper);
        DownloadAuditPageQueryRequest request = new DownloadAuditPageQueryRequest();
        request.setOwnerId("other-user");
        request.setResourceType(" FILE ");
        request.setResourceId(" file-1 ");

        DownloadAuditPageResponse response = service.page("user-1", false, request);

        assertEquals(1L, response.getTotal());
        assertEquals("audit-1", response.getRecords().get(0).getAuditId());
        verify(mapper).listPage(eq("user-1"), eq("FILE"), eq("file-1"), any(), any(), eq(0), eq(20));
    }

    @Test
    void adminCanFilterOwner() {
        DownloadAuditRecordMapper mapper = mock(DownloadAuditRecordMapper.class);
        DownloadAuditQueryServiceImpl service = new DownloadAuditQueryServiceImpl(mapper);
        DownloadAuditPageQueryRequest request = new DownloadAuditPageQueryRequest();
        request.setOwnerId("target-user");
        request.setPageSize(500);

        service.page("admin", true, request);

        verify(mapper).listPage(eq("target-user"), eq(null), eq(null), any(), any(), eq(0), eq(100));
    }

    @Test
    void pageRejectsInvalidTimeRange() {
        DownloadAuditQueryServiceImpl service = new DownloadAuditQueryServiceImpl(
                mock(DownloadAuditRecordMapper.class));
        DownloadAuditPageQueryRequest request = new DownloadAuditPageQueryRequest();
        request.setCreatedFrom(LocalDateTime.now());
        request.setCreatedTo(LocalDateTime.now().minusDays(1));

        assertThrows(IllegalArgumentException.class, () -> service.page("user-1", false, request));
    }
}
