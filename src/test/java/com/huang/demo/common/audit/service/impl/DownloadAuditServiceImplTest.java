package com.huang.demo.common.audit.service.impl;

import com.huang.demo.common.audit.domain.entity.DownloadAuditRecord;
import com.huang.demo.common.audit.repository.DownloadAuditRecordMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DownloadAuditServiceImplTest {

    @Test
    void recordSignedDownloadPersistsAuditRecord() {
        DownloadAuditRecordMapper mapper = mock(DownloadAuditRecordMapper.class);
        DownloadAuditServiceImpl service = new DownloadAuditServiceImpl(mapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "10.0.0.1, 10.0.0.2");
        request.addHeader("User-Agent", "JUnit");

        service.recordSignedDownload("user-1", "EXPORT", "task-1",
                "excel/student/student-demo.xlsx", "student-demo.xlsx", request);

        ArgumentCaptor<DownloadAuditRecord> captor = ArgumentCaptor.forClass(DownloadAuditRecord.class);
        verify(mapper).insert(captor.capture());
        DownloadAuditRecord record = captor.getValue();
        assertNotNull(record.getAuditId());
        assertEquals("user-1", record.getOwnerId());
        assertEquals("EXPORT", record.getResourceType());
        assertEquals("task-1", record.getResourceId());
        assertEquals("excel/student/student-demo.xlsx", record.getObjectKey());
        assertEquals("student-demo.xlsx", record.getFileName());
        assertEquals("10.0.0.1", record.getRequestIp());
        assertEquals("JUnit", record.getUserAgent());
        assertNotNull(record.getCreatedAt());
    }

    @Test
    void recordSignedDownloadIgnoresAuditFailure() {
        DownloadAuditRecordMapper mapper = mock(DownloadAuditRecordMapper.class);
        doThrow(new IllegalStateException("db down")).when(mapper).insert(any(DownloadAuditRecord.class));
        DownloadAuditServiceImpl service = new DownloadAuditServiceImpl(mapper);

        service.recordSignedDownload("user-1", "FILE", "file-1", null, null, null);

        verify(mapper).insert(any(DownloadAuditRecord.class));
    }
}
