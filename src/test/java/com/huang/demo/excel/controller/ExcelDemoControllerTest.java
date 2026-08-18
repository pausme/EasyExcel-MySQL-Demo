package com.huang.demo.excel.controller;

import com.huang.demo.excel.api.dto.ExportTaskResponse;
import com.huang.demo.excel.api.dto.ImportErrorPreviewResponse;
import com.huang.demo.excel.api.dto.ImportTaskResponse;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.domain.model.ExportTask;
import com.huang.demo.excel.domain.model.ExportTaskStatus;
import com.huang.demo.excel.domain.model.StudentExportFormat;
import com.huang.demo.excel.service.ExportTaskService;
import com.huang.demo.excel.service.StudentImportTaskService;
import com.huang.demo.excel.service.StudentService;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.domain.model.AsyncTaskStatus;
import com.huang.demo.task.domain.model.AsyncTaskType;
import com.huang.demo.task.service.TaskOwnerResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExcelDemoControllerTest {

    @Mock
    private StudentService studentService;

    @Mock
    private ExportTaskService exportTaskService;

    @Mock
    private StudentImportTaskService studentImportTaskService;

    @Mock
    private TaskOwnerResolver taskOwnerResolver;

    @Mock
    private HttpServletRequest request;

    private ExcelDemoController controller;

    @BeforeEach
    void setUp() {
        controller = new ExcelDemoController(
                studentService, new ExcelDemoProperties(), exportTaskService, studentImportTaskService, taskOwnerResolver);
        when(taskOwnerResolver.resolve(request)).thenReturn("anonymous");
    }

    @Test
    void submitExportPassesRequestedFormat() {
        ExportTask task = ExportTask.builder()
                .taskId("task-1")
                .ownerId("anonymous")
                .status(ExportTaskStatus.QUEUED)
                .format(StudentExportFormat.CSV)
                .fileName("student-demo-task-1.csv")
                .build();
        when(exportTaskService.submitExport("anonymous", "CSV")).thenReturn(task);

        ExportTaskResponse response = controller.submitExport("CSV", request);

        assertEquals("task-1", response.getTaskId());
        assertEquals(StudentExportFormat.CSV, response.getFormat());
        verify(exportTaskService).submitExport("anonymous", "CSV");
    }

    @Test
    void downloadExportReturnsPresignedRedirect() {
        ExportTask task = ExportTask.builder()
                .taskId("task-1")
                .ownerId("anonymous")
                .status(ExportTaskStatus.SUCCESS)
                .fileName("student-demo.xlsx")
                .objectKey("excel/student/student-demo.xlsx")
                .build();
        String downloadUrl = "http://minio.example.com/student-demo.xlsx";
        when(exportTaskService.findTask("task-1")).thenReturn(Optional.of(task));
        when(exportTaskService.createDownloadUrl(task)).thenReturn(Optional.of(downloadUrl));

        ResponseEntity<Void> response = controller.downloadExport("task-1", request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals(URI.create(downloadUrl), response.getHeaders().getLocation());
        verify(exportTaskService).createDownloadUrl(task);
    }

    @Test
    void downloadExportRejectsUnfinishedTask() {
        ExportTask task = ExportTask.builder()
                .taskId("task-1")
                .ownerId("anonymous")
                .status(ExportTaskStatus.RUNNING)
                .build();
        when(exportTaskService.findTask("task-1")).thenReturn(Optional.of(task));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.downloadExport("task-1", request));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    @Test
    void importExcelSubmitsAsyncTask() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "student.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});
        AsyncTaskRecord task = AsyncTaskRecord.builder()
                .taskId("task-1")
                .ownerId("anonymous")
                .taskType(AsyncTaskType.IMPORT.name())
                .taskName("学生数据导入")
                .businessKey("business-1")
                .status(AsyncTaskStatus.CREATED.name())
                .progressPercent(0)
                .totalCount(0L)
                .completedCount(0L)
                .retryCount(0)
                .maxRetryCount(3)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .expireAt(LocalDateTime.now().plusHours(1))
                .build();
        when(studentImportTaskService.submitImport(any(MockMultipartFile.class), eq("anonymous"))).thenReturn(task);

        ImportTaskResponse response = controller.importExcel(file, request);

        assertEquals("task-1", response.getTaskId());
        assertEquals("CREATED", response.getStatus());
        verify(studentImportTaskService).submitImport(file, "anonymous");
    }

    @Test
    void importStatusReturnsMyImportTask() {
        ImportTaskResponse expected = ImportTaskResponse.builder()
                .taskId("task-1")
                .ownerId("anonymous")
                .status("FAILED")
                .errorCount(1)
                .errorFileName("student-import-error-task-1.xlsx")
                .hasErrorFile(true)
                .build();
        when(studentImportTaskService.findImportTask("task-1", "anonymous")).thenReturn(Optional.of(expected));

        ImportTaskResponse response = controller.importStatus("task-1", request);

        assertEquals("task-1", response.getTaskId());
        assertEquals("student-import-error-task-1.xlsx", response.getErrorFileName());
    }

    @Test
    void downloadImportErrorFileReturnsPresignedRedirect() {
        String downloadUrl = "http://minio.example.com/student-import-error-task-1.xlsx";
        when(studentImportTaskService.createErrorFileDownloadUrl("task-1", "anonymous"))
                .thenReturn(Optional.of(downloadUrl));

        ResponseEntity<Void> response = controller.downloadImportErrorFile("task-1", request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals(URI.create(downloadUrl), response.getHeaders().getLocation());
    }

    @Test
    void previewImportErrorsReturnsRows() {
        ImportErrorPreviewResponse expected = ImportErrorPreviewResponse.builder()
                .taskId("task-1")
                .errorCount(1)
                .hasErrorFile(true)
                .errorSummary(Collections.singletonMap("学号不能为空", 1))
                .rows(Collections.emptyList())
                .build();
        when(studentImportTaskService.previewImportErrors("task-1", "anonymous", 10))
                .thenReturn(Optional.of(expected));

        ImportErrorPreviewResponse response = controller.previewImportErrors("task-1", 10, request);

        assertEquals("task-1", response.getTaskId());
        assertEquals(Integer.valueOf(1), response.getErrorSummary().get("学号不能为空"));
    }
}
