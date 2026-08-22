package com.huang.demo.file.service.impl;

import com.huang.demo.common.compensation.domain.entity.CompensationRecord;
import com.huang.demo.common.compensation.service.CompensationService;
import com.huang.demo.common.lock.DistributedLockService;
import com.huang.demo.file.config.FileCenterProperties;
import com.huang.demo.file.domain.entity.FileRecord;
import com.huang.demo.file.domain.entity.FileUploadTask;
import com.huang.demo.file.domain.model.FileReconciliationResult;
import com.huang.demo.file.repository.FileRecordMapper;
import com.huang.demo.file.repository.FileUploadTaskMapper;
import com.huang.demo.file.service.FileObjectStorageService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileObjectReconciliationServiceImplTest {

    @Test
    void reconcileMarksMissingFilesAndRecordsOrphanObjects() {
        FileRecordMapper fileRecordMapper = mock(FileRecordMapper.class);
        FileUploadTaskMapper uploadTaskMapper = mock(FileUploadTaskMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        CompensationService compensationService = mock(CompensationService.class);
        DistributedLockService lockService = mock(DistributedLockService.class);
        FileCenterProperties properties = new FileCenterProperties();
        properties.setReconciliationBatchSize(2);
        properties.setReconciliationUploadStaleHours(24);
        FileObjectReconciliationServiceImpl service = newService(
                fileRecordMapper, uploadTaskMapper, storageService,
                compensationService, lockService, properties);

        FileRecord missingFile = FileRecord.builder()
                .id(1L)
                .fileId("file-1")
                .ownerId("user-1")
                .objectKey("files/general/missing.txt")
                .status("NORMAL")
                .build();
        FileUploadTask staleTask = FileUploadTask.builder()
                .id(2L)
                .uploadId("upload-1")
                .objectKey("files/general/pending.txt")
                .partObjectPrefix("files/multipart/upload-1")
                .status("UPLOADING")
                .createdAt(LocalDateTime.now().minusDays(2))
                .build();
        when(fileRecordMapper.listAllAfterId(0L, 2))
                .thenReturn(Collections.singletonList(missingFile));
        when(fileRecordMapper.listNormalAfterId(0L, 2))
                .thenReturn(Collections.singletonList(missingFile));
        when(uploadTaskMapper.listAllAfterId(0L, 2))
                .thenReturn(Collections.singletonList(staleTask));
        when(uploadTaskMapper.listUploadingAfterId(0L, 2))
                .thenReturn(Collections.singletonList(staleTask));
        when(fileRecordMapper.markDeleted("user-1", "file-1")).thenReturn(1);
        when(storageService.listObjectKeys("files/general"))
                .thenReturn(Collections.singletonList("files/general/orphan.txt"));
        when(storageService.listObjectKeys("files/multipart"))
                .thenReturn(Arrays.asList(
                        "files/multipart/upload-1/00001.part",
                        "files/multipart/orphan/00001.part"));
        when(storageService.listObjectKeys("files/multipart/upload-1"))
                .thenReturn(Collections.singletonList("files/multipart/upload-1/00001.part"));
        when(storageService.statObject("files/general/pending.txt"))
                .thenThrow(new IllegalStateException("not found"));
        when(compensationService.recordPending(
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompensationRecord.builder().compensationId("comp-1").build());

        FileReconciliationResult result = service.reconcileOnce();

        assertEquals(1L, result.getFileRecordsChecked());
        assertEquals(1L, result.getMissingFileRecords());
        assertEquals(1L, result.getUploadTasksChecked());
        assertEquals(1L, result.getExpiredUploadTasks());
        assertEquals(3L, result.getOrphanObjects());
        assertEquals(4L, result.getCompensationRecords());
        verify(fileRecordMapper).markDeleted("user-1", "file-1");
        verify(compensationService).recordPending(
                "FILE", "file-1", "OBJECT_MISSING", "objectKey=files/general/missing.txt");
        verify(compensationService).recordPending(
                "FILE_UPLOAD", "upload-1", "ORPHAN_OBJECT",
                "objectKey=files/general/pending.txt,partCount=1");
        verify(compensationService).recordPending(
                "FILE", "files/general/orphan.txt", "ORPHAN_OBJECT",
                "objectKey=files/general/orphan.txt,prefix=files/general");
        verify(compensationService).recordPending(
                "FILE_UPLOAD", "files/multipart/orphan/00001.part", "ORPHAN_OBJECT",
                "objectKey=files/multipart/orphan/00001.part,prefix=files/multipart");
    }

    @Test
    void objectListFailureDoesNotMarkFilesAsMissing() {
        FileRecordMapper fileRecordMapper = mock(FileRecordMapper.class);
        FileUploadTaskMapper uploadTaskMapper = mock(FileUploadTaskMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        CompensationService compensationService = mock(CompensationService.class);
        DistributedLockService lockService = mock(DistributedLockService.class);
        FileCenterProperties properties = new FileCenterProperties();
        FileObjectReconciliationServiceImpl service = newService(
                fileRecordMapper, uploadTaskMapper, storageService,
                compensationService, lockService, properties);
        FileRecord file = FileRecord.builder()
                .id(1L)
                .fileId("file-1")
                .ownerId("user-1")
                .objectKey("files/general/file.txt")
                .status("NORMAL")
                .build();
        when(fileRecordMapper.listAllAfterId(0L, 200))
                .thenReturn(Collections.singletonList(file));
        when(fileRecordMapper.listNormalAfterId(0L, 200))
                .thenReturn(Collections.singletonList(file));
        when(storageService.listObjectKeys("files/general"))
                .thenThrow(new IllegalStateException("minio unavailable"));
        when(storageService.listObjectKeys("files/multipart"))
                .thenReturn(Collections.<String>emptyList());
        when(compensationService.recordPending(
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompensationRecord.builder().compensationId("comp-1").build());

        FileReconciliationResult result = service.reconcileOnce();

        assertEquals(0L, result.getMissingFileRecords());
        assertEquals(1L, result.getCleanupFailures());
        assertEquals(1L, result.getCompensationRecords());
        verify(fileRecordMapper, never()).markDeleted(anyString(), anyString());
        verify(compensationService).recordPending(
                "FILE_STORAGE", "files/general", "CLEANUP_OBJECT_FAILED",
                "objectPrefix=files/general,error=minio unavailable");
    }

    @Test
    void reconcileSkipsWhenDistributedLockIsHeld() {
        FileRecordMapper fileRecordMapper = mock(FileRecordMapper.class);
        FileUploadTaskMapper uploadTaskMapper = mock(FileUploadTaskMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        CompensationService compensationService = mock(CompensationService.class);
        DistributedLockService lockService = mock(DistributedLockService.class);
        when(lockService.tryLock(anyString(), anyString(), any())).thenReturn(false);
        FileObjectReconciliationServiceImpl service = newService(
                fileRecordMapper, uploadTaskMapper, storageService,
                compensationService, lockService, new FileCenterProperties());

        FileReconciliationResult result = service.reconcileOnceWithLock();

        assertEquals(0L, result.getFileRecordsChecked());
        verify(fileRecordMapper, never()).listAllAfterId(anyLong(), anyInt());
        verify(uploadTaskMapper, never()).listAllAfterId(anyLong(), anyInt());
    }

    private FileObjectReconciliationServiceImpl newService(FileRecordMapper fileRecordMapper,
                                                            FileUploadTaskMapper uploadTaskMapper,
                                                            FileObjectStorageService storageService,
                                                            CompensationService compensationService,
                                                            DistributedLockService lockService,
                                                            FileCenterProperties properties) {
        return new FileObjectReconciliationServiceImpl(
                fileRecordMapper, uploadTaskMapper, storageService,
                properties, compensationService, lockService);
    }
}
