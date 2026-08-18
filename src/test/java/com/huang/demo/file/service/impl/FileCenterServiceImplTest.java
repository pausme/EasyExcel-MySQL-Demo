package com.huang.demo.file.service.impl;

import com.huang.demo.file.api.dto.DirectUploadInitRequest;
import com.huang.demo.file.api.dto.DirectUploadInitResponse;
import com.huang.demo.file.api.dto.FilePageResponse;
import com.huang.demo.file.api.dto.InstantUploadCheckRequest;
import com.huang.demo.file.api.dto.InstantUploadCheckResponse;
import com.huang.demo.file.api.dto.MultipartPartsResponse;
import com.huang.demo.file.api.dto.MultipartUploadInitRequest;
import com.huang.demo.file.api.dto.MultipartUploadInitResponse;
import com.huang.demo.file.config.FileCenterProperties;
import com.huang.demo.file.domain.entity.FileRecord;
import com.huang.demo.file.domain.entity.FileUploadTask;
import com.huang.demo.file.domain.model.StoredFile;
import com.huang.demo.file.domain.model.StoredObject;
import com.huang.demo.file.repository.FileRecordMapper;
import com.huang.demo.file.repository.FileUploadTaskMapper;
import com.huang.demo.file.service.FileObjectStorageService;
import com.huang.demo.file.service.FileSecurityScanner;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileCenterServiceImplTest {

    @Test
    void uploadStoresFileRecord() throws Exception {
        FileRecordMapper mapper = mock(FileRecordMapper.class);
        FileUploadTaskMapper taskMapper = mock(FileUploadTaskMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        FileCenterServiceImpl service = newService(mapper, taskMapper, storageService);
        MockMultipartFile file = new MockMultipartFile("file", "demo.txt",
                "text/plain", "hello".getBytes());
        when(storageService.upload(any(), any(Long.class), any(String.class), any(String.class))).thenReturn(
                StoredFile.builder()
                        .bucketName("student-excel")
                        .objectKey("files/general/2026/08/05/file-id.txt")
                        .fileMd5("5d41402abc4b2a76b9719d911017c592")
                        .fileSize(file.getSize())
                        .build());
        when(mapper.insert(any(FileRecord.class))).thenReturn(1);

        FileRecord record = service.upload(file);

        assertEquals("demo.txt", record.getOriginalName());
        assertEquals("NORMAL", record.getStatus());
        assertNotNull(record.getFileId());
        verify(mapper).insert(any(FileRecord.class));
    }

    @Test
    void uploadRejectsExecutableContentEvenIfExtensionLooksSafe() throws Exception {
        FileRecordMapper mapper = mock(FileRecordMapper.class);
        FileUploadTaskMapper taskMapper = mock(FileUploadTaskMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        FileCenterServiceImpl service = newService(mapper, taskMapper, storageService);
        MockMultipartFile file = new MockMultipartFile("file", "evil.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{'M', 'Z', 0, 0, 0, 0});

        IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> service.upload(file));

        assertTrue(exception.getMessage().contains("可执行程序"));
        verify(storageService, org.mockito.Mockito.never()).upload(any(), any(Long.class), any(String.class), any(String.class));
        verify(mapper, org.mockito.Mockito.never()).insert(any(FileRecord.class));
    }

    @Test
    void deleteMarksDeletedAndClearsObjectAfterCommit() {
        FileRecordMapper mapper = mock(FileRecordMapper.class);
        FileUploadTaskMapper taskMapper = mock(FileUploadTaskMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        FileCenterServiceImpl service = newService(mapper, taskMapper, storageService);
        FileRecord record = FileRecord.builder()
                .fileId("file-1")
                .originalName("demo.xlsx")
                .objectKey("files/general/2026/08/05/file-1.xlsx")
                .status("NORMAL")
                .build();
        when(mapper.findNormalByFileId("anonymous", "file-1")).thenReturn(Optional.of(record));
        when(mapper.markDeleted("anonymous", "file-1")).thenReturn(1);

        service.delete("file-1");

        verify(mapper).markDeleted("anonymous", "file-1");
        verify(storageService).deleteQuietly("files/general/2026/08/05/file-1.xlsx");
    }

    @Test
    void pageReturnsRecords() {
        FileRecordMapper mapper = mock(FileRecordMapper.class);
        FileUploadTaskMapper taskMapper = mock(FileUploadTaskMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        FileCenterServiceImpl service = newService(mapper, taskMapper, storageService);
        when(mapper.countNormal("anonymous", null, null)).thenReturn(1L);
        when(mapper.listNormalPage("anonymous", null, null, 0, 20)).thenReturn(java.util.Collections.singletonList(
                FileRecord.builder()
                        .fileId("file-1")
                        .originalName("demo.xlsx")
                        .status("NORMAL")
                        .build()));

        FilePageResponse response = service.page(null);

        assertEquals(1L, response.getTotal());
        assertEquals(1, response.getRecords().size());
        assertTrue(response.getRecords().get(0).getOriginalName().equals("demo.xlsx"));
    }

    @Test
    void instantCheckReturnsExistingFile() {
        FileRecordMapper mapper = mock(FileRecordMapper.class);
        FileUploadTaskMapper taskMapper = mock(FileUploadTaskMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        FileCenterServiceImpl service = newService(mapper, taskMapper, storageService);
        InstantUploadCheckRequest request = new InstantUploadCheckRequest();
        request.setFileMd5("5d41402abc4b2a76b9719d911017c592");
        request.setFileSize(5L);
        when(mapper.findNormalByMd5AndSize("anonymous", "5d41402abc4b2a76b9719d911017c592", 5L)).thenReturn(Optional.of(
                FileRecord.builder().fileId("file-1").originalName("demo.txt").status("NORMAL").build()));

        InstantUploadCheckResponse response = service.instantCheck(request);

        assertTrue(response.isExists());
        assertEquals("file-1", response.getFile().getFileId());
    }

    @Test
    void initDirectUploadReturnsPresignedUrl() {
        FileRecordMapper mapper = mock(FileRecordMapper.class);
        FileUploadTaskMapper taskMapper = mock(FileUploadTaskMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        FileCenterServiceImpl service = newService(mapper, taskMapper, storageService);
        DirectUploadInitRequest request = new DirectUploadInitRequest();
        request.setOriginalName("demo.txt");
        request.setContentType("text/plain");
        request.setFileMd5("5d41402abc4b2a76b9719d911017c592");
        request.setFileSize(5L);
        when(mapper.findNormalByMd5AndSize("anonymous", "5d41402abc4b2a76b9719d911017c592", 5L)).thenReturn(Optional.empty());
        when(storageService.bucketName()).thenReturn("student-excel");
        when(storageService.createUploadUrl(any(String.class))).thenReturn("http://minio/upload-url");

        DirectUploadInitResponse response = service.initDirectUpload(request);

        assertEquals(false, response.isInstant());
        assertNotNull(response.getUploadId());
        assertEquals("http://minio/upload-url", response.getUploadUrl());
        verify(taskMapper).insert(any(FileUploadTask.class));
    }

    @Test
    void initDirectUploadRejectsDangerousExtension() {
        FileRecordMapper mapper = mock(FileRecordMapper.class);
        FileUploadTaskMapper taskMapper = mock(FileUploadTaskMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        FileCenterServiceImpl service = newService(mapper, taskMapper, storageService);
        DirectUploadInitRequest request = new DirectUploadInitRequest();
        request.setOriginalName("demo.exe");
        request.setContentType("application/octet-stream");
        request.setFileMd5("5d41402abc4b2a76b9719d911017c592");
        request.setFileSize(5L);

        IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> service.initDirectUpload(request));

        assertTrue(exception.getMessage().contains("不被允许"));
        verify(taskMapper, org.mockito.Mockito.never()).insert(any(FileUploadTask.class));
    }

    @Test
    void completeDirectUploadCreatesFileRecord() {
        FileRecordMapper mapper = mock(FileRecordMapper.class);
        FileUploadTaskMapper taskMapper = mock(FileUploadTaskMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        FileCenterServiceImpl service = newService(mapper, taskMapper, storageService);
        FileUploadTask task = FileUploadTask.builder()
                .uploadId("upload-1")
                .fileId("file-1")
                .uploadType("DIRECT")
                .originalName("demo.txt")
                .objectKey("files/general/file-1.txt")
                .bucketName("student-excel")
                .contentType("text/plain")
                .fileSize(5L)
                .fileMd5("5d41402abc4b2a76b9719d911017c592")
                .fileExt("txt")
                .status("UPLOADING")
                .build();
        when(taskMapper.findByUploadId("anonymous", "upload-1")).thenReturn(Optional.of(task));
        when(storageService.statObject("files/general/file-1.txt")).thenReturn(
                StoredObject.builder().objectKey("files/general/file-1.txt").size(5L).etag("etag").build());
        when(storageService.openObject("files/general/file-1.txt")).thenReturn(new ByteArrayInputStream("hello".getBytes()));
        when(taskMapper.markSuccess("anonymous", "upload-1")).thenReturn(1);

        FileRecord record = service.completeDirectUpload("upload-1");

        assertEquals("file-1", record.getFileId());
        verify(mapper).insert(any(FileRecord.class));
    }

    @Test
    void completeDirectUploadRejectsSuspiciousStoredObject() {
        FileRecordMapper mapper = mock(FileRecordMapper.class);
        FileUploadTaskMapper taskMapper = mock(FileUploadTaskMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        FileCenterServiceImpl service = newService(mapper, taskMapper, storageService);
        FileUploadTask task = FileUploadTask.builder()
                .uploadId("upload-1")
                .fileId("file-1")
                .uploadType("DIRECT")
                .originalName("evil.xlsx")
                .objectKey("files/general/file-1.xlsx")
                .bucketName("student-excel")
                .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .fileSize(6L)
                .fileMd5("5d41402abc4b2a76b9719d911017c592")
                .fileExt("xlsx")
                .status("UPLOADING")
                .build();
        when(taskMapper.findByUploadId("anonymous", "upload-1")).thenReturn(Optional.of(task));
        when(storageService.statObject("files/general/file-1.xlsx")).thenReturn(
                StoredObject.builder().objectKey("files/general/file-1.xlsx").size(6L)
                        .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        .etag("etag").build());
        when(storageService.openObject("files/general/file-1.xlsx")).thenReturn(new ByteArrayInputStream(
                new byte[]{'M', 'Z', 0, 0, 0, 0}));

        IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> service.completeDirectUpload("upload-1"));

        assertTrue(exception.getMessage().contains("可执行程序"));
        verify(mapper, org.mockito.Mockito.never()).insert(any(FileRecord.class));
        verify(taskMapper, org.mockito.Mockito.never()).markSuccess("anonymous", "upload-1");
    }

    @Test
    void createDownloadUrlRejectsExpiredObject() {
        FileRecordMapper mapper = mock(FileRecordMapper.class);
        FileUploadTaskMapper taskMapper = mock(FileUploadTaskMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        FileCenterServiceImpl service = newService(mapper, taskMapper, storageService);
        FileRecord record = FileRecord.builder()
                .fileId("file-1")
                .originalName("demo.txt")
                .objectKey("files/general/file-1.txt")
                .status("NORMAL")
                .build();
        when(mapper.findNormalByFileId("anonymous", "file-1")).thenReturn(Optional.of(record));
        when(storageService.statObject("files/general/file-1.txt"))
                .thenThrow(new IllegalStateException("not found"));

        Optional<String> downloadUrl = service.createDownloadUrl("file-1");

        assertTrue(!downloadUrl.isPresent());
        verify(mapper).markDeleted("anonymous", "file-1");
        verify(storageService, org.mockito.Mockito.never()).createDownloadUrl(any(String.class), any(String.class));
    }

    @Test
    void initMultipartUploadReturnsPartUrls() {
        FileRecordMapper mapper = mock(FileRecordMapper.class);
        FileUploadTaskMapper taskMapper = mock(FileUploadTaskMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        FileCenterServiceImpl service = newService(mapper, taskMapper, storageService);
        MultipartUploadInitRequest request = new MultipartUploadInitRequest();
        request.setOriginalName("large.bin");
        request.setContentType("application/octet-stream");
        request.setFileMd5("5d41402abc4b2a76b9719d911017c592");
        request.setFileSize(12L * 1024L * 1024L);
        request.setPartSize(6L * 1024L * 1024L);
        when(mapper.findNormalByMd5AndSize("anonymous", "5d41402abc4b2a76b9719d911017c592", 12L * 1024L * 1024L))
                .thenReturn(Optional.empty());
        when(storageService.bucketName()).thenReturn("student-excel");
        when(storageService.createUploadUrl(any(String.class))).thenReturn("http://minio/part-url");

        MultipartUploadInitResponse response = service.initMultipartUpload(request);

        assertEquals(false, response.isInstant());
        assertEquals(2, response.getPartCount());
        assertEquals(2, response.getParts().size());
        verify(taskMapper).insert(any(FileUploadTask.class));
    }

    @Test
    void listMultipartPartsReturnsUploadedPartNumbers() {
        FileRecordMapper mapper = mock(FileRecordMapper.class);
        FileUploadTaskMapper taskMapper = mock(FileUploadTaskMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        FileCenterServiceImpl service = newService(mapper, taskMapper, storageService);
        FileUploadTask task = FileUploadTask.builder()
                .uploadId("upload-1")
                .uploadType("MULTIPART")
                .partObjectPrefix("files/multipart/upload-1")
                .partCount(2)
                .status("UPLOADING")
                .build();
        when(taskMapper.findByUploadId("anonymous", "upload-1")).thenReturn(Optional.of(task));
        when(storageService.listObjectKeys("files/multipart/upload-1")).thenReturn(java.util.Arrays.asList(
                "files/multipart/upload-1/00002.part",
                "files/multipart/upload-1/00001.part"));

        MultipartPartsResponse response = service.listMultipartParts("upload-1");

        assertEquals(2, response.getUploadedParts().size());
        assertEquals(Integer.valueOf(1), response.getUploadedParts().get(0));
    }

    private FileCenterServiceImpl newService(FileRecordMapper mapper,
                                             FileUploadTaskMapper taskMapper,
                                             FileObjectStorageService storageService) {
        FileCenterProperties properties = new FileCenterProperties();
        properties.setInitEnabled(false);
        FileSecurityScanner scanner = new RuleBasedFileSecurityScanner(properties);
        return new FileCenterServiceImpl(mapper, taskMapper, storageService, scanner, properties);
    }
}
