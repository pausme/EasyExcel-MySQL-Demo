package com.huang.demo.excel.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.config.MinioProperties;
import com.huang.demo.excel.domain.model.ExportTask;
import com.huang.demo.excel.domain.model.ExportTaskStatus;
import com.huang.demo.excel.report.ReportExportEngine;
import com.huang.demo.excel.service.MinioObjectStorageService;
import com.huang.demo.task.service.TaskCenterService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportTaskServiceImplTest {

    @Test
    void createDownloadUrlMarksTaskExpiredWhenExportObjectMissing() {
        TaskCenterService taskCenterService = mock(TaskCenterService.class);
        MinioObjectStorageService minioObjectStorageService = mock(MinioObjectStorageService.class);
        ExportTaskServiceImpl service = new ExportTaskServiceImpl(
                new ExcelDemoProperties(),
                Runnable::run,
                taskCenterService,
                new ObjectMapper().findAndRegisterModules(),
                minioObjectStorageService,
                new MinioProperties(),
                mock(ReportExportEngine.class),
                mock(StudentReportExportJob.class));
        ExportTask task = ExportTask.builder()
                .taskId("task-1")
                .status(ExportTaskStatus.SUCCESS)
                .fileName("student-demo.xlsx")
                .objectKey("excel/student/student-demo.xlsx")
                .build();
        doThrow(new IllegalStateException("MinIO 文件不存在或已过期"))
                .when(minioObjectStorageService)
                .ensureObjectExists("excel/student/student-demo.xlsx");

        Optional<String> downloadUrl = service.createDownloadUrl(task);

        assertFalse(downloadUrl.isPresent());
        verify(taskCenterService).markExpired(
                "task-1",
                "导出文件不存在或已过期",
                "导出文件已被清理，可重试任务或重新提交导出");
    }
}
