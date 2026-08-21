package com.huang.demo.file.controller;

import com.huang.demo.common.audit.service.DownloadAuditService;
import com.huang.demo.common.idempotency.service.IdempotencyService;
import com.huang.demo.common.idempotency.service.IdempotentAction;
import com.huang.demo.file.api.dto.DirectUploadInitRequest;
import com.huang.demo.file.api.dto.DirectUploadInitResponse;
import com.huang.demo.file.service.FileCenterService;
import com.huang.demo.task.service.TaskOwnerResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileCenterControllerTest {

    private FileCenterService fileCenterService;
    private TaskOwnerResolver taskOwnerResolver;
    private IdempotencyService idempotencyService;
    private HttpServletRequest request;
    private FileCenterController controller;

    @BeforeEach
    void setUp() throws Exception {
        fileCenterService = mock(FileCenterService.class);
        taskOwnerResolver = mock(TaskOwnerResolver.class);
        DownloadAuditService downloadAuditService = mock(DownloadAuditService.class);
        idempotencyService = mock(IdempotencyService.class);
        request = mock(HttpServletRequest.class);
        controller = new FileCenterController(fileCenterService, taskOwnerResolver, downloadAuditService, idempotencyService);
        lenient().when(taskOwnerResolver.resolve(request)).thenReturn("user-1");
        lenient().when(idempotencyService.fingerprint(any())).thenReturn("fingerprint");
        lenient().when(idempotencyService.execute(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> ((IdempotentAction<?>) invocation.getArgument(5)).execute());
    }

    @Test
    void directUploadInitUsesIdempotencyKey() throws Exception {
        DirectUploadInitRequest uploadRequest = new DirectUploadInitRequest();
        uploadRequest.setOriginalName("demo.txt");
        uploadRequest.setContentType("text/plain");
        uploadRequest.setFileSize(5L);
        uploadRequest.setFileMd5("5d41402abc4b2a76b9719d911017c592");
        when(fileCenterService.initDirectUpload(uploadRequest)).thenReturn(DirectUploadInitResponse.builder()
                .instant(false)
                .uploadId("upload-1")
                .fileId("file-1")
                .uploadUrl("http://minio/upload")
                .build());

        DirectUploadInitResponse response = controller.initDirectUpload(uploadRequest, "key-1", request);

        assertEquals("upload-1", response.getUploadId());
        verify(idempotencyService).execute(org.mockito.Mockito.eq("user-1"),
                org.mockito.Mockito.eq("FILE_DIRECT_INIT"),
                org.mockito.Mockito.eq("key-1"),
                org.mockito.Mockito.eq("fingerprint"),
                org.mockito.Mockito.eq(DirectUploadInitResponse.class),
                any());
    }
}
