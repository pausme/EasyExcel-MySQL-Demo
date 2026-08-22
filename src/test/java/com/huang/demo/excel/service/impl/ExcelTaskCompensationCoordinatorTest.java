package com.huang.demo.excel.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.common.compensation.service.CompensationService;
import com.huang.demo.common.lock.DistributedLockService;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.service.MinioObjectStorageService;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.repository.AsyncTaskRecordMapper;
import com.huang.demo.task.service.TaskCenterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExcelTaskCompensationCoordinatorTest {

    private AsyncTaskRecordMapper taskRecordMapper;
    private TaskCenterService taskCenterService;
    private MinioObjectStorageService minioObjectStorageService;
    private CompensationService compensationService;
    private DistributedLockService distributedLockService;
    private ExcelTaskCompensationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        taskRecordMapper = mock(AsyncTaskRecordMapper.class);
        taskCenterService = mock(TaskCenterService.class);
        minioObjectStorageService = mock(MinioObjectStorageService.class);
        compensationService = mock(CompensationService.class);
        distributedLockService = mock(DistributedLockService.class);
        coordinator = new ExcelTaskCompensationCoordinator(
                taskRecordMapper,
                taskCenterService,
                minioObjectStorageService,
                compensationService,
                distributedLockService,
                new ObjectMapper().findAndRegisterModules(),
                new ExcelDemoProperties());
    }

    @Test
    void missingExportObjectMarksTaskExpiredAndRecordsCompensation() {
        AsyncTaskRecord task = AsyncTaskRecord.builder()
                .id(1L)
                .taskId("export-1")
                .taskType("EXPORT")
                .status("SUCCESS")
                .resultPayload("{\"objectKey\":\"excel/student/export-1.xlsx\"}")
                .build();
        when(taskRecordMapper.listByTypeAndStatusAfterId(eq("EXPORT"), eq("SUCCESS"), eq(0L), anyInt()))
                .thenReturn(Collections.singletonList(task));
        doThrow(new IllegalStateException("MinIO 文件不存在或已过期"))
                .when(minioObjectStorageService).ensureObjectExists("excel/student/export-1.xlsx");

        long handled = coordinator.compensateOnce();

        assertEquals(1L, handled);
        verify(compensationService).recordPending(
                "EXPORT", "export-1", "OBJECT_MISSING",
                "objectKey=excel/student/export-1.xlsx,reason=导出任务成功但结果对象不存在");
        verify(taskCenterService).markExpired(
                "export-1", "导出文件不存在或已过期",
                "导出文件已被清理，可重试任务或重新提交导出");
    }

    @Test
    void missingImportErrorObjectRecordsCompensationWithoutChangingFailedTask() {
        AsyncTaskRecord task = AsyncTaskRecord.builder()
                .id(2L)
                .taskId("import-1")
                .taskType("IMPORT")
                .status("FAILED")
                .resultPayload("{\"errorObjectKey\":\"excel/student/import-error/import-1.xlsx\"}")
                .build();
        when(taskRecordMapper.listByTypeAndStatusAfterId(eq("EXPORT"), eq("SUCCESS"), eq(0L), anyInt()))
                .thenReturn(Collections.<AsyncTaskRecord>emptyList());
        when(taskRecordMapper.listByTypeAndStatusAfterId(eq("IMPORT"), eq("FAILED"), eq(0L), anyInt()))
                .thenReturn(Collections.singletonList(task));
        doThrow(new IllegalStateException("MinIO 文件不存在或已过期"))
                .when(minioObjectStorageService).ensureObjectExists("excel/student/import-error/import-1.xlsx");

        long handled = coordinator.compensateOnce();

        assertEquals(1L, handled);
        verify(compensationService).recordPending(
                "IMPORT", "import-1", "OBJECT_MISSING",
                "objectKey=excel/student/import-error/import-1.xlsx,reason=导入错误明细任务失败但结果对象不存在");
        verify(taskCenterService, never()).markExpired(anyString(), anyString(), anyString());
    }

    @Test
    void dependencyFailureDoesNotCreateFalseMissingObjectCompensation() {
        AsyncTaskRecord task = AsyncTaskRecord.builder()
                .id(3L)
                .taskId("import-2")
                .taskType("IMPORT")
                .status("FAILED")
                .failureType("DEPENDENCY_ERROR")
                .requestPayload("{\"sourceObjectKey\":\"excel/student/import-source/import-2.xlsx\"}")
                .build();
        when(taskRecordMapper.listByTypeAndStatusAfterId(eq("EXPORT"), eq("SUCCESS"), eq(0L), anyInt()))
                .thenReturn(Collections.<AsyncTaskRecord>emptyList());
        when(taskRecordMapper.listByTypeAndStatusAfterId(eq("IMPORT"), eq("FAILED"), eq(0L), anyInt()))
                .thenReturn(Collections.singletonList(task));
        doThrow(new IllegalStateException("MinIO 服务连接失败"))
                .when(minioObjectStorageService).ensureObjectExists("excel/student/import-source/import-2.xlsx");

        long handled = coordinator.compensateOnce();

        assertEquals(0L, handled);
        verify(compensationService, never()).recordPending(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void lockHeldSkipsCompensationScan() {
        when(distributedLockService.tryLock(anyString(), anyString(), any())).thenReturn(false);

        long handled = coordinator.compensateOnceWithLock();

        assertEquals(0L, handled);
        verify(taskRecordMapper, never()).listByTypeAndStatusAfterId(anyString(), anyString(), anyLong(), anyInt());
    }
}
