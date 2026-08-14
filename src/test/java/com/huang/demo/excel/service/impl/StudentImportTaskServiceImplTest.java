package com.huang.demo.excel.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.config.MinioProperties;
import com.huang.demo.excel.domain.model.StudentImportProgressCallback;
import com.huang.demo.excel.domain.model.StudentImportResult;
import com.huang.demo.excel.domain.model.StudentImportValidationException;
import com.huang.demo.excel.model.StudentImportErrorRow;
import com.huang.demo.excel.service.MinioObjectStorageService;
import com.huang.demo.excel.service.StudentService;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.domain.model.AsyncTaskStatus;
import com.huang.demo.task.domain.model.AsyncTaskType;
import com.huang.demo.task.domain.model.CreateAsyncTaskCommand;
import com.huang.demo.task.service.TaskCenterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentImportTaskServiceImplTest {

    @TempDir
    private Path tempDir;

    @Test
    void submitImportCreatesTaskAndExecutesImport() throws Exception {
        StudentService studentService = mock(StudentService.class);
        TaskCenterService taskCenterService = mock(TaskCenterService.class);
        MinioObjectStorageService minioObjectStorageService = mock(MinioObjectStorageService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportTempDir(tempDir.toString());
        properties.setImportBatchSize(2000);
        MinioProperties minioProperties = new MinioProperties();
        minioProperties.setImportErrorObjectPrefix("excel/student/import-error");
        minioProperties.setImportSourceObjectPrefix("excel/student/import-source");

        when(taskCenterService.createTask(any(CreateAsyncTaskCommand.class))).thenAnswer(invocation -> {
            CreateAsyncTaskCommand command = invocation.getArgument(0);
            return AsyncTaskRecord.builder()
                    .taskId("task-1")
                    .ownerId("user-1")
                    .taskType(command.getTaskType().name())
                    .taskName(command.getTaskName())
                    .businessKey(command.getBusinessKey())
                    .status(AsyncTaskStatus.CREATED.name())
                    .progressPercent(0)
                    .totalCount(0L)
                    .completedCount(0L)
                    .retryCount(0)
                    .maxRetryCount(3)
                    .requestPayload(command.getRequestPayload())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .expireAt(LocalDateTime.now().plusHours(1))
                    .build();
        });
        when(taskCenterService.currentWorkerId()).thenReturn("worker-1");
        when(taskCenterService.markRunning(eq("task-1"), eq("worker-1"))).thenAnswer(invocation -> {
            ArgumentCaptor<CreateAsyncTaskCommand> captor = ArgumentCaptor.forClass(CreateAsyncTaskCommand.class);
            verify(taskCenterService).createTask(captor.capture());
            return AsyncTaskRecord.builder()
                    .taskId("task-1")
                    .ownerId("user-1")
                    .taskType(AsyncTaskType.IMPORT.name())
                    .taskName("学生数据导入")
                    .status(AsyncTaskStatus.RUNNING.name())
                    .progressPercent(0)
                    .totalCount(0L)
                    .completedCount(0L)
                    .retryCount(0)
                    .maxRetryCount(3)
                    .requestPayload(captor.getValue().getRequestPayload())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .expireAt(LocalDateTime.now().plusHours(1))
                    .build();
        });
        when(minioObjectStorageService.openObject(anyString())).thenAnswer(invocation ->
                new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(studentService.importExcel(any(InputStream.class), eq(2000), any(StudentImportProgressCallback.class)))
                .thenReturn(StudentImportResult.builder().importedCount(2).batchCount(1).build());
        when(taskCenterService.markSuccess(eq("task-1"), any())).thenReturn(AsyncTaskRecord.builder()
                .taskId("task-1")
                .status(AsyncTaskStatus.SUCCESS.name())
                .build());

        StudentImportTaskServiceImpl service = new StudentImportTaskServiceImpl(
                studentService, properties, new DirectThreadPoolTaskExecutor(), taskCenterService, objectMapper,
                minioObjectStorageService, minioProperties);
        MockMultipartFile file = new MockMultipartFile("file", "student.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});

        AsyncTaskRecord submittedTask = service.submitImport(file, "user-1");

        assertEquals("task-1", submittedTask.getTaskId());
        ArgumentCaptor<CreateAsyncTaskCommand> createCaptor = ArgumentCaptor.forClass(CreateAsyncTaskCommand.class);
        verify(taskCenterService).createTask(createCaptor.capture());
        assertEquals(AsyncTaskType.IMPORT, createCaptor.getValue().getTaskType());
        assertTrue(createCaptor.getValue().getRequestPayload().contains("excel/student/import-source"));
        verify(studentService).importExcel(any(InputStream.class), eq(2000), any(StudentImportProgressCallback.class));
        verify(minioObjectStorageService).uploadExcel(any(InputStream.class), eq(3L), anyString());
        verify(minioObjectStorageService).openObject(anyString());
        verify(taskCenterService).markSuccess(eq("task-1"), any());
    }

    @Test
    void validationFailureUploadsErrorFileAndMarksTaskFailed() throws Exception {
        StudentService studentService = mock(StudentService.class);
        TaskCenterService taskCenterService = mock(TaskCenterService.class);
        MinioObjectStorageService minioObjectStorageService = mock(MinioObjectStorageService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportTempDir(tempDir.toString());
        properties.setImportBatchSize(2000);
        MinioProperties minioProperties = new MinioProperties();
        minioProperties.setImportErrorObjectPrefix("excel/student/import-error");
        minioProperties.setImportSourceObjectPrefix("excel/student/import-source");

        when(taskCenterService.createTask(any(CreateAsyncTaskCommand.class))).thenAnswer(invocation -> {
            CreateAsyncTaskCommand command = invocation.getArgument(0);
            return AsyncTaskRecord.builder()
                    .taskId("task-2")
                    .ownerId("user-1")
                    .taskType(command.getTaskType().name())
                    .taskName(command.getTaskName())
                    .businessKey(command.getBusinessKey())
                    .status(AsyncTaskStatus.CREATED.name())
                    .progressPercent(0)
                    .totalCount(0L)
                    .completedCount(0L)
                    .retryCount(0)
                    .maxRetryCount(3)
                    .requestPayload(command.getRequestPayload())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .expireAt(LocalDateTime.now().plusHours(1))
                    .build();
        });
        when(taskCenterService.currentWorkerId()).thenReturn("worker-1");
        when(taskCenterService.markRunning(eq("task-2"), eq("worker-1"))).thenAnswer(invocation -> {
            ArgumentCaptor<CreateAsyncTaskCommand> captor = ArgumentCaptor.forClass(CreateAsyncTaskCommand.class);
            verify(taskCenterService).createTask(captor.capture());
            return AsyncTaskRecord.builder()
                    .taskId("task-2")
                    .ownerId("user-1")
                    .taskType(AsyncTaskType.IMPORT.name())
                    .taskName("学生数据导入")
                    .status(AsyncTaskStatus.RUNNING.name())
                    .progressPercent(0)
                    .totalCount(0L)
                    .completedCount(0L)
                    .retryCount(0)
                    .maxRetryCount(3)
                    .requestPayload(captor.getValue().getRequestPayload())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .expireAt(LocalDateTime.now().plusHours(1))
                    .build();
        });
        when(minioObjectStorageService.openObject(anyString())).thenAnswer(invocation ->
                new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(studentService.importExcel(any(InputStream.class), eq(2000), any(StudentImportProgressCallback.class)))
                .thenThrow(new StudentImportValidationException("导入文件校验失败，errorRows=1",
                        Collections.singletonList(StudentImportErrorRow.builder()
                                .rowNo(1)
                                .studentNo("")
                                .name("张三")
                                .errorMessage("学号不能为空")
                                .build())));
        when(taskCenterService.markFailed(eq("task-2"), eq("导入文件校验失败，errorRows=1"), any()))
                .thenReturn(AsyncTaskRecord.builder()
                        .taskId("task-2")
                        .status(AsyncTaskStatus.FAILED.name())
                        .build());

        StudentImportTaskServiceImpl service = new StudentImportTaskServiceImpl(
                studentService, properties, new DirectThreadPoolTaskExecutor(), taskCenterService, objectMapper,
                minioObjectStorageService, minioProperties);
        MockMultipartFile file = new MockMultipartFile("file", "student.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});

        service.submitImport(file, "user-1");

        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskCenterService).markFailed(eq("task-2"), eq("导入文件校验失败，errorRows=1"), resultCaptor.capture());
        assertTrue(resultCaptor.getValue().contains("student-import-error-task-2.xlsx"));
        verify(minioObjectStorageService).uploadExcel(any(InputStream.class), eq(3L), anyString());
        verify(minioObjectStorageService).openObject(anyString());
        verify(minioObjectStorageService).uploadExcel(any(Path.class), eq("excel/student/import-error/student-import-error-task-2.xlsx"));
    }

    private static class DirectThreadPoolTaskExecutor extends ThreadPoolTaskExecutor {

        @Override
        public void execute(Runnable task) {
            task.run();
        }
    }
}
