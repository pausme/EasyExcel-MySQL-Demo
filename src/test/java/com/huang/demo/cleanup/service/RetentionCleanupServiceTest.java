package com.huang.demo.cleanup.service;

import com.huang.demo.common.compensation.service.CompensationService;
import com.huang.demo.cleanup.config.CleanupProperties;
import com.huang.demo.cleanup.domain.CleanupResult;
import com.huang.demo.excel.repository.StudentMapper;
import com.huang.demo.file.domain.entity.FileRecord;
import com.huang.demo.file.domain.entity.FileUploadTask;
import com.huang.demo.file.repository.FileRecordMapper;
import com.huang.demo.file.repository.FileUploadTaskMapper;
import com.huang.demo.file.service.FileObjectStorageService;
import com.huang.demo.common.lock.DistributedLockService;
import com.huang.demo.task.repository.AsyncTaskRecordMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetentionCleanupServiceTest {

    @Test
    void cleanupOnceDeletesExpiredMetadataAndDeletedFileObjects() {
        CleanupProperties properties = new CleanupProperties();
        properties.setBatchSize(2);
        AsyncTaskRecordMapper taskMapper = mock(AsyncTaskRecordMapper.class);
        FileUploadTaskMapper uploadTaskMapper = mock(FileUploadTaskMapper.class);
        FileRecordMapper fileRecordMapper = mock(FileRecordMapper.class);
        StudentMapper studentMapper = mock(StudentMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        DistributedLockService distributedLockService = mock(DistributedLockService.class);
        RetentionCleanupService service = new RetentionCleanupService(
                properties, taskMapper, uploadTaskMapper, fileRecordMapper, studentMapper, storageService,
                distributedLockService);

        FileRecord first = FileRecord.builder().id(1L).objectKey("files/general/a.txt").build();
        FileRecord second = FileRecord.builder().id(2L).objectKey("files/general/b.txt").build();
        FileUploadTask expiredUploadTask = FileUploadTask.builder()
                .id(10L)
                .uploadId("upload-1")
                .objectKey("files/general/pending.bin")
                .partObjectPrefix("files/multipart/upload-1")
                .build();
        when(taskMapper.deleteTerminalBefore(any(), anyInt())).thenReturn(3);
        when(uploadTaskMapper.deleteFinishedBefore(any(), anyInt())).thenReturn(4);
        when(uploadTaskMapper.listUploadingBefore(any(), anyInt())).thenReturn(Arrays.asList(expiredUploadTask));
        when(uploadTaskMapper.deleteById(10L)).thenReturn(1);
        when(storageService.listObjectKeys("files/multipart/upload-1"))
                .thenReturn(Arrays.asList("files/multipart/upload-1/00001.part"));
        when(fileRecordMapper.listDeletedBefore(any(), anyInt())).thenReturn(Arrays.asList(first, second));
        when(fileRecordMapper.deleteById(1L)).thenReturn(1);
        when(fileRecordMapper.deleteById(2L)).thenReturn(1);
        when(studentMapper.deleteImportStageBefore(any(), anyInt())).thenReturn(5);
        when(studentMapper.deleteExpiredStudentVersions(anyInt(), anyInt())).thenReturn(6);
        when(distributedLockService.tryLock(anyString(), anyString(), any())).thenReturn(true);

        CleanupResult result = service.cleanupOnce();

        assertEquals(3, result.getExpiredTasks());
        assertEquals(5, result.getUploadTasks());
        assertEquals(2, result.getDeletedFiles());
        assertEquals(5, result.getImportStageRows());
        assertEquals(6, result.getImportVersionRows());
        verify(storageService).deleteQuietly("files/general/a.txt");
        verify(storageService).deleteQuietly("files/general/b.txt");
        verify(storageService).deleteQuietly("files/general/pending.bin");
        verify(storageService).deleteQuietly(Arrays.asList("files/multipart/upload-1/00001.part"));
        verify(taskMapper).deleteTerminalBefore(any(), org.mockito.Mockito.eq(2));
        verify(uploadTaskMapper).listUploadingBefore(any(), org.mockito.Mockito.eq(2));
        verify(uploadTaskMapper).deleteById(10L);
        verify(uploadTaskMapper).deleteFinishedBefore(any(), org.mockito.Mockito.eq(2));
        verify(fileRecordMapper).listDeletedBefore(any(), org.mockito.Mockito.eq(2));
        verify(studentMapper).deleteImportStageBefore(any(), org.mockito.Mockito.eq(2));
        verify(studentMapper).deleteExpiredStudentVersions(org.mockito.Mockito.eq(1), org.mockito.Mockito.eq(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cleanupOnceWithLockSkipsWhenAnotherWorkerHoldsLock() {
        CleanupProperties properties = new CleanupProperties();
        AsyncTaskRecordMapper taskMapper = mock(AsyncTaskRecordMapper.class);
        FileUploadTaskMapper uploadTaskMapper = mock(FileUploadTaskMapper.class);
        FileRecordMapper fileRecordMapper = mock(FileRecordMapper.class);
        StudentMapper studentMapper = mock(StudentMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        DistributedLockService distributedLockService = mock(DistributedLockService.class);
        when(distributedLockService.tryLock(anyString(), anyString(), any())).thenReturn(false);
        RetentionCleanupService service = new RetentionCleanupService(
                properties, taskMapper, uploadTaskMapper, fileRecordMapper, studentMapper, storageService,
                distributedLockService);

        CleanupResult result = service.cleanupOnceWithLock();

        assertEquals(0, result.getExpiredTasks());
        assertEquals(0, result.getUploadTasks());
        assertEquals(0, result.getDeletedFiles());
        assertEquals(0, result.getImportStageRows());
        assertEquals(0, result.getImportVersionRows());
        verify(taskMapper, never()).deleteTerminalBefore(any(), anyInt());
        verify(uploadTaskMapper, never()).listUploadingBefore(any(), anyInt());
        verify(uploadTaskMapper, never()).deleteFinishedBefore(any(), anyInt());
        verify(fileRecordMapper, never()).listDeletedBefore(any(), anyInt());
        verify(studentMapper, never()).deleteImportStageBefore(any(), anyInt());
        verify(studentMapper, never()).deleteExpiredStudentVersions(anyInt(), anyInt());
    }

    @Test
    void cleanupOnceSkipsImportVersionCleanupWhenDisabled() {
        CleanupProperties properties = new CleanupProperties();
        properties.setImportVersionCleanupEnabled(false);
        AsyncTaskRecordMapper taskMapper = mock(AsyncTaskRecordMapper.class);
        FileUploadTaskMapper uploadTaskMapper = mock(FileUploadTaskMapper.class);
        FileRecordMapper fileRecordMapper = mock(FileRecordMapper.class);
        StudentMapper studentMapper = mock(StudentMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        DistributedLockService distributedLockService = mock(DistributedLockService.class);
        RetentionCleanupService service = new RetentionCleanupService(
                properties, taskMapper, uploadTaskMapper, fileRecordMapper, studentMapper, storageService,
                distributedLockService);

        CleanupResult result = service.cleanupOnce();

        assertEquals(0, result.getImportVersionRows());
        verify(studentMapper, never()).deleteExpiredStudentVersions(anyInt(), anyInt());
    }

    @Test
    void multipartObjectListFailureCreatesCompensationRecord() {
        CleanupProperties properties = new CleanupProperties();
        AsyncTaskRecordMapper taskMapper = mock(AsyncTaskRecordMapper.class);
        FileUploadTaskMapper uploadTaskMapper = mock(FileUploadTaskMapper.class);
        FileRecordMapper fileRecordMapper = mock(FileRecordMapper.class);
        StudentMapper studentMapper = mock(StudentMapper.class);
        FileObjectStorageService storageService = mock(FileObjectStorageService.class);
        DistributedLockService distributedLockService = mock(DistributedLockService.class);
        CompensationService compensationService = mock(CompensationService.class);
        RetentionCleanupService service = new RetentionCleanupService(
                properties, taskMapper, uploadTaskMapper, fileRecordMapper, studentMapper, storageService,
                distributedLockService, compensationService);
        FileUploadTask task = FileUploadTask.builder()
                .id(10L)
                .uploadId("upload-1")
                .objectKey("files/general/pending.bin")
                .partObjectPrefix("files/multipart/upload-1")
                .build();
        when(taskMapper.deleteTerminalBefore(any(), anyInt())).thenReturn(0);
        when(uploadTaskMapper.listUploadingBefore(any(), anyInt())).thenReturn(java.util.Collections.singletonList(task));
        when(uploadTaskMapper.deleteFinishedBefore(any(), anyInt())).thenReturn(0);
        when(uploadTaskMapper.deleteById(10L)).thenReturn(1);
        when(storageService.listObjectKeys("files/multipart/upload-1"))
                .thenThrow(new IllegalStateException("minio unavailable"));

        service.cleanupOnce();

        verify(compensationService).recordPending(
                "FILE_UPLOAD",
                "upload-1",
                "CLEANUP_OBJECT_FAILED",
                "objectKey=files/multipart/upload-1,error=minio unavailable");
        verify(uploadTaskMapper).deleteById(10L);
    }
}
