package com.huang.demo.cleanup.service;

import com.huang.demo.cleanup.config.CleanupProperties;
import com.huang.demo.cleanup.domain.CleanupResult;
import com.huang.demo.excel.repository.StudentMapper;
import com.huang.demo.file.domain.entity.FileRecord;
import com.huang.demo.file.repository.FileRecordMapper;
import com.huang.demo.file.repository.FileUploadTaskMapper;
import com.huang.demo.file.service.FileObjectStorageService;
import com.huang.demo.task.repository.AsyncTaskRecordMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
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
        RetentionCleanupService service = new RetentionCleanupService(
                properties, taskMapper, uploadTaskMapper, fileRecordMapper, studentMapper, storageService);

        FileRecord first = FileRecord.builder().id(1L).objectKey("files/general/a.txt").build();
        FileRecord second = FileRecord.builder().id(2L).objectKey("files/general/b.txt").build();
        when(taskMapper.deleteTerminalBefore(any(), anyInt())).thenReturn(3);
        when(uploadTaskMapper.deleteFinishedBefore(any(), anyInt())).thenReturn(4);
        when(fileRecordMapper.listDeletedBefore(any(), anyInt())).thenReturn(Arrays.asList(first, second));
        when(fileRecordMapper.deleteById(1L)).thenReturn(1);
        when(fileRecordMapper.deleteById(2L)).thenReturn(1);
        when(studentMapper.deleteImportStageBefore(any(), anyInt())).thenReturn(5);

        CleanupResult result = service.cleanupOnce();

        assertEquals(3, result.getExpiredTasks());
        assertEquals(4, result.getUploadTasks());
        assertEquals(2, result.getDeletedFiles());
        assertEquals(5, result.getImportStageRows());
        verify(storageService).deleteQuietly("files/general/a.txt");
        verify(storageService).deleteQuietly("files/general/b.txt");
        verify(taskMapper).deleteTerminalBefore(any(), org.mockito.Mockito.eq(2));
        verify(uploadTaskMapper).deleteFinishedBefore(any(), org.mockito.Mockito.eq(2));
        verify(fileRecordMapper).listDeletedBefore(any(), org.mockito.Mockito.eq(2));
        verify(studentMapper).deleteImportStageBefore(any(), org.mockito.Mockito.eq(2));
    }
}
