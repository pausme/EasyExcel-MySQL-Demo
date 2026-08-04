package com.huang.demo.excel.controller;

import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.domain.model.ExportTask;
import com.huang.demo.excel.domain.model.ExportTaskStatus;
import com.huang.demo.excel.service.ExportTaskService;
import com.huang.demo.excel.service.StudentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExcelDemoControllerTest {

    @Mock
    private StudentService studentService;

    @Mock
    private ExportTaskService exportTaskService;

    private ExcelDemoController controller;

    @BeforeEach
    void setUp() {
        controller = new ExcelDemoController(studentService, new ExcelDemoProperties(), exportTaskService);
    }

    @Test
    void downloadExportReturnsPresignedRedirect() {
        ExportTask task = ExportTask.builder()
                .taskId("task-1")
                .status(ExportTaskStatus.SUCCESS)
                .fileName("student-demo.xlsx")
                .objectKey("excel/student/student-demo.xlsx")
                .build();
        String downloadUrl = "http://minio.example.com/student-demo.xlsx";
        when(exportTaskService.findTask("task-1")).thenReturn(Optional.of(task));
        when(exportTaskService.createDownloadUrl(task)).thenReturn(Optional.of(downloadUrl));

        ResponseEntity<Void> response = controller.downloadExport("task-1");

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals(URI.create(downloadUrl), response.getHeaders().getLocation());
        verify(exportTaskService).createDownloadUrl(task);
    }

    @Test
    void downloadExportRejectsUnfinishedTask() {
        ExportTask task = ExportTask.builder()
                .taskId("task-1")
                .status(ExportTaskStatus.RUNNING)
                .build();
        when(exportTaskService.findTask("task-1")).thenReturn(Optional.of(task));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.downloadExport("task-1"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }
}
