package com.huang.demo.excel.service.impl;

import com.huang.demo.excel.api.dto.StudentReportRunCreateRequest;
import com.huang.demo.excel.api.dto.StudentReportRunResponse;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.domain.entity.StudentReportRun;
import com.huang.demo.excel.domain.model.ExportTask;
import com.huang.demo.excel.domain.model.ExportTaskStatus;
import com.huang.demo.excel.domain.model.StudentExportQuery;
import com.huang.demo.excel.domain.model.StudentReportRunStatus;
import com.huang.demo.excel.repository.StudentReportRunMapper;
import com.huang.demo.excel.service.ExportTaskService;
import com.huang.demo.task.api.dto.AsyncTaskPageQueryRequest;
import com.huang.demo.task.api.dto.AsyncTaskPageResponse;
import com.huang.demo.task.domain.model.AsyncTaskType;
import com.huang.demo.task.service.TaskCenterService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentReportRunServiceImplTest {

    @Test
    void createPersistsNormalRunControl() {
        StudentReportRunMapper runMapper = mock(StudentReportRunMapper.class);
        ExportTaskService exportTaskService = mock(ExportTaskService.class);
        TaskCenterService taskCenterService = mock(TaskCenterService.class);
        when(runMapper.findNormalByOwnerAndCode("user-1", "class-a")).thenReturn(Optional.empty());

        StudentReportRunCreateRequest request = new StudentReportRunCreateRequest();
        request.setRunControlCode("class-a");
        request.setRunName("一班学生");
        request.setClassName("一班");
        request.setMinAge(10);
        request.setMaxAge(20);

        StudentReportRunServiceImpl service = new StudentReportRunServiceImpl(
                runMapper, exportTaskService, taskCenterService, new ExcelDemoProperties());
        StudentReportRunResponse response = service.create("user-1", request);

        ArgumentCaptor<StudentReportRun> captor = ArgumentCaptor.forClass(StudentReportRun.class);
        verify(runMapper).insert(captor.capture());
        assertEquals("class-a", captor.getValue().getRunControlCode());
        assertEquals("一班", captor.getValue().getClassName());
        assertEquals(StudentReportRunStatus.NORMAL.name(), captor.getValue().getStatus());
        assertEquals(0L, captor.getValue().getDeleted());
        assertEquals("class-a", response.getRunControlCode());
    }

    @Test
    void runSubmitsExportTaskWithRunIdAsBusinessKey() {
        StudentReportRunMapper runMapper = mock(StudentReportRunMapper.class);
        ExportTaskService exportTaskService = mock(ExportTaskService.class);
        TaskCenterService taskCenterService = mock(TaskCenterService.class);
        StudentReportRun run = buildRun();
        when(runMapper.findByRunId("run-1")).thenReturn(Optional.of(run));
        when(exportTaskService.submitExport(eq("user-1"), eq("run-1"), eq("学生报表导出-一班学生"),
                any(StudentExportQuery.class))).thenReturn(ExportTask.builder()
                .taskId("task-1")
                .ownerId("user-1")
                .status(ExportTaskStatus.QUEUED)
                .fileName("student-demo-run-1.xlsx")
                .build());

        StudentReportRunServiceImpl service = new StudentReportRunServiceImpl(
                runMapper, exportTaskService, taskCenterService, new ExcelDemoProperties());
        ExportTask task = service.run("user-1", "run-1");

        assertEquals("task-1", task.getTaskId());
        ArgumentCaptor<StudentExportQuery> queryCaptor = ArgumentCaptor.forClass(StudentExportQuery.class);
        verify(exportTaskService).submitExport(eq("user-1"), eq("run-1"), eq("学生报表导出-一班学生"),
                queryCaptor.capture());
        assertEquals("一班", queryCaptor.getValue().getClassName());
    }

    @Test
    void pageTasksUsesRunIdBusinessKey() {
        StudentReportRunMapper runMapper = mock(StudentReportRunMapper.class);
        ExportTaskService exportTaskService = mock(ExportTaskService.class);
        TaskCenterService taskCenterService = mock(TaskCenterService.class);
        when(runMapper.findByRunId("run-1")).thenReturn(Optional.of(buildRun()));
        AsyncTaskPageResponse expected = AsyncTaskPageResponse.builder()
                .total(0)
                .pageNo(1)
                .pageSize(20)
                .records(Collections.emptyList())
                .build();
        when(taskCenterService.pageMyTasksByBusinessKey(eq("user-1"), eq(AsyncTaskType.EXPORT.name()),
                eq("run-1"), any(AsyncTaskPageQueryRequest.class))).thenReturn(expected);

        StudentReportRunServiceImpl service = new StudentReportRunServiceImpl(
                runMapper, exportTaskService, taskCenterService, new ExcelDemoProperties());
        AsyncTaskPageResponse response = service.pageTasks("user-1", "run-1", new AsyncTaskPageQueryRequest());

        assertEquals(0, response.getTotal());
        verify(taskCenterService).pageMyTasksByBusinessKey(eq("user-1"), eq(AsyncTaskType.EXPORT.name()),
                eq("run-1"), any(AsyncTaskPageQueryRequest.class));
    }

    @Test
    void deleteMarksRunDeletedWithId() {
        StudentReportRunMapper runMapper = mock(StudentReportRunMapper.class);
        ExportTaskService exportTaskService = mock(ExportTaskService.class);
        TaskCenterService taskCenterService = mock(TaskCenterService.class);
        when(runMapper.findByRunId("run-1")).thenReturn(Optional.of(buildRun()));
        when(runMapper.update(any(StudentReportRun.class))).thenReturn(1);

        StudentReportRunServiceImpl service = new StudentReportRunServiceImpl(
                runMapper, exportTaskService, taskCenterService, new ExcelDemoProperties());

        assertTrue(service.delete("user-1", "run-1"));
        ArgumentCaptor<StudentReportRun> captor = ArgumentCaptor.forClass(StudentReportRun.class);
        verify(runMapper).update(captor.capture());
        assertEquals(StudentReportRunStatus.DELETED.name(), captor.getValue().getStatus());
        assertEquals(1L, captor.getValue().getDeleted());
    }

    private StudentReportRun buildRun() {
        return StudentReportRun.builder()
                .id(1L)
                .runId("run-1")
                .ownerId("user-1")
                .runControlCode("class-a")
                .runName("一班学生")
                .className("一班")
                .status(StudentReportRunStatus.NORMAL.name())
                .deleted(0L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
