package com.huang.demo.common.compensation.service.impl;

import com.huang.demo.common.compensation.domain.entity.CompensationRecord;
import com.huang.demo.excel.service.MinioObjectStorageService;
import com.huang.demo.file.service.FileObjectStorageService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObjectCleanupCompensationHandlerTest {

    @Test
    void supportsOnlyCleanupRecordsWithObjectKey() {
        ObjectCleanupCompensationHandler handler = newHandler();

        assertTrue(handler.supports(record("FILE", "ORPHAN_OBJECT", "objectKey=files/general/a.txt")));
        assertTrue(handler.supports(record("EXPORT", "CLEANUP_OBJECT_FAILED", "objectKey=excel/student/a.xlsx")));
        assertFalse(handler.supports(record("FILE", "OBJECT_MISSING", "objectKey=files/general/a.txt")));
        assertFalse(handler.supports(record("FILE", "ORPHAN_OBJECT", "reason=missing")));
    }

    @Test
    void fileCenterObjectIsDeletedThroughFileStorage() {
        FileObjectStorageService fileStorage = mock(FileObjectStorageService.class);
        ObjectCleanupCompensationHandler handler = new ObjectCleanupCompensationHandler(
                mock(MinioObjectStorageService.class), fileStorage);

        handler.handle(record("FILE", "ORPHAN_OBJECT", "objectKey=files/general/a.txt,prefix=files/general"));

        verify(fileStorage).deleteQuietly("files/general/a.txt");
    }

    @Test
    void fileCenterPrefixListsAndDeletesPartObjects() {
        FileObjectStorageService fileStorage = mock(FileObjectStorageService.class);
        when(fileStorage.listObjectKeys("files/multipart/upload-1/"))
                .thenReturn(Arrays.asList("files/multipart/upload-1/00001.part"));
        ObjectCleanupCompensationHandler handler = new ObjectCleanupCompensationHandler(
                mock(MinioObjectStorageService.class), fileStorage);

        handler.handle(record("FILE_UPLOAD", "CLEANUP_OBJECT_FAILED",
                "objectKey=files/multipart/upload-1/,error=timeout"));

        verify(fileStorage).listObjectKeys("files/multipart/upload-1/");
        verify(fileStorage).deleteQuietly(Arrays.asList("files/multipart/upload-1/00001.part"));
        verify(fileStorage).deleteQuietly("files/multipart/upload-1/");
    }

    @Test
    void excelObjectIsDeletedThroughExcelStorage() {
        MinioObjectStorageService excelStorage = mock(MinioObjectStorageService.class);
        ObjectCleanupCompensationHandler handler = new ObjectCleanupCompensationHandler(
                excelStorage, mock(FileObjectStorageService.class));

        handler.handle(record("EXPORT", "ORPHAN_OBJECT", "objectKey=excel/student/a.xlsx"));

        verify(excelStorage).deleteQuietly("excel/student/a.xlsx");
    }

    private ObjectCleanupCompensationHandler newHandler() {
        return new ObjectCleanupCompensationHandler(
                mock(MinioObjectStorageService.class), mock(FileObjectStorageService.class));
    }

    private CompensationRecord record(String bizType, String failureType, String payload) {
        return CompensationRecord.builder()
                .compensationId("comp-1")
                .bizType(bizType)
                .bizId("biz-1")
                .failureType(failureType)
                .payload(payload)
                .build();
    }
}
