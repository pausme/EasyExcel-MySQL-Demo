package com.huang.demo.excel.controller;

import com.huang.demo.excel.api.dto.ExportTaskResponse;
import com.huang.demo.excel.api.dto.StudentReportRunCreateRequest;
import com.huang.demo.excel.api.dto.StudentReportRunResponse;
import com.huang.demo.excel.domain.model.ExportTask;
import com.huang.demo.excel.domain.model.ExportTaskStatus;
import com.huang.demo.excel.service.StudentReportRunService;
import com.huang.demo.task.service.TaskOwnerResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentReportRunControllerTest {

    @Mock
    private StudentReportRunService studentReportRunService;

    @Mock
    private TaskOwnerResolver taskOwnerResolver;

    @Mock
    private HttpServletRequest request;

    private StudentReportRunController controller;

    @BeforeEach
    void setUp() {
        controller = new StudentReportRunController(studentReportRunService, taskOwnerResolver);
        when(taskOwnerResolver.resolve(request)).thenReturn("user-1");
    }

    @Test
    void createDelegatesToServiceWithOwner() {
        StudentReportRunResponse expected = StudentReportRunResponse.builder()
                .runId("run-1")
                .ownerId("user-1")
                .runControlCode("class-a")
                .runName("一班学生")
                .build();
        when(studentReportRunService.create(eq("user-1"), any(StudentReportRunCreateRequest.class)))
                .thenReturn(expected);

        StudentReportRunResponse response = controller.create(new StudentReportRunCreateRequest(), request);

        assertEquals("run-1", response.getRunId());
        verify(studentReportRunService).create(eq("user-1"), any(StudentReportRunCreateRequest.class));
    }

    @Test
    void runReturnsExportTaskResponse() {
        when(studentReportRunService.run("user-1", "run-1")).thenReturn(ExportTask.builder()
                .taskId("task-1")
                .ownerId("user-1")
                .status(ExportTaskStatus.QUEUED)
                .fileName("student-demo-run-1.xlsx")
                .build());

        ExportTaskResponse response = controller.run("run-1", request);

        assertEquals("task-1", response.getTaskId());
        verify(studentReportRunService).run("user-1", "run-1");
    }
}
