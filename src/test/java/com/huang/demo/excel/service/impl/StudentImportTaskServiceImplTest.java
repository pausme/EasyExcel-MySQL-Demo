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

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        byte[] xlsxBytes = buildMinimalXlsxBytes();
        MockMultipartFile file = new MockMultipartFile("file", "student.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsxBytes);

        AsyncTaskRecord submittedTask = service.submitImport(file, "user-1");

        assertEquals("task-1", submittedTask.getTaskId());
        ArgumentCaptor<CreateAsyncTaskCommand> createCaptor = ArgumentCaptor.forClass(CreateAsyncTaskCommand.class);
        verify(taskCenterService).createTask(createCaptor.capture());
        assertEquals(AsyncTaskType.IMPORT, createCaptor.getValue().getTaskType());
        assertTrue(createCaptor.getValue().getRequestPayload().contains("excel/student/import-source"));
        verify(studentService).importExcel(any(InputStream.class), eq(2000), any(StudentImportProgressCallback.class));
        verify(minioObjectStorageService).uploadExcel(any(InputStream.class), eq((long) xlsxBytes.length), anyString());
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
        byte[] xlsxBytes = buildMinimalXlsxBytes();
        MockMultipartFile file = new MockMultipartFile("file", "student.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsxBytes);

        service.submitImport(file, "user-1");

        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskCenterService).markFailed(eq("task-2"), eq("导入文件校验失败，errorRows=1"), resultCaptor.capture());
        assertTrue(resultCaptor.getValue().contains("student-import-error-task-2.xlsx"));
        verify(minioObjectStorageService).uploadExcel(any(InputStream.class), eq((long) xlsxBytes.length), anyString());
        verify(minioObjectStorageService).openObject(anyString());
        verify(minioObjectStorageService).uploadExcel(any(Path.class), eq("excel/student/import-error/student-import-error-task-2.xlsx"));
    }

    @Test
    void submitImportRejectsNonExcelBeforeCreatingTask() throws Exception {
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

        StudentImportTaskServiceImpl service = new StudentImportTaskServiceImpl(
                studentService, properties, new DirectThreadPoolTaskExecutor(), taskCenterService, objectMapper,
                minioObjectStorageService, minioProperties);
        MockMultipartFile file = new MockMultipartFile("file", "not-excel.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "not excel".getBytes("UTF-8"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.submitImport(file, "user-1"));

        assertEquals("导入文件格式错误，请上传 .xlsx 文件", exception.getMessage());
        verify(taskCenterService, never()).createTask(any(CreateAsyncTaskCommand.class));
        verify(minioObjectStorageService, never()).uploadExcel(any(InputStream.class), anyLong(), anyString());
        verify(studentService, never()).importExcel(any(InputStream.class), anyInt(), any(StudentImportProgressCallback.class));
    }

    @Test
    void submitImportRejectsRowsExceedingLimitBeforeCreatingTask() throws Exception {
        StudentService studentService = mock(StudentService.class);
        TaskCenterService taskCenterService = mock(TaskCenterService.class);
        MinioObjectStorageService minioObjectStorageService = mock(MinioObjectStorageService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportTempDir(tempDir.toString());
        properties.setImportBatchSize(2000);
        properties.setImportMaxRowsPerTask(1);
        MinioProperties minioProperties = new MinioProperties();
        minioProperties.setImportErrorObjectPrefix("excel/student/import-error");
        minioProperties.setImportSourceObjectPrefix("excel/student/import-source");

        StudentImportTaskServiceImpl service = new StudentImportTaskServiceImpl(
                studentService, properties, new DirectThreadPoolTaskExecutor(), taskCenterService, objectMapper,
                minioObjectStorageService, minioProperties);
        MockMultipartFile file = new MockMultipartFile("file", "student.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", buildXlsxWithWorksheetRows(3));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.submitImport(file, "user-1"));

        assertTrue(exception.getMessage().contains("导入文件数据行数超过限制"));
        verify(taskCenterService, never()).createTask(any(CreateAsyncTaskCommand.class));
        verify(minioObjectStorageService, never()).uploadExcel(any(InputStream.class), anyLong(), anyString());
    }

    @Test
    void submitImportRejectsFileSizeExceedingLimitBeforeCreatingTask() throws Exception {
        StudentService studentService = mock(StudentService.class);
        TaskCenterService taskCenterService = mock(TaskCenterService.class);
        MinioObjectStorageService minioObjectStorageService = mock(MinioObjectStorageService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportTempDir(tempDir.toString());
        properties.setImportBatchSize(2000);
        properties.setImportMaxFileSizeForAsync(10L);
        MinioProperties minioProperties = new MinioProperties();
        minioProperties.setImportErrorObjectPrefix("excel/student/import-error");
        minioProperties.setImportSourceObjectPrefix("excel/student/import-source");

        StudentImportTaskServiceImpl service = new StudentImportTaskServiceImpl(
                studentService, properties, new DirectThreadPoolTaskExecutor(), taskCenterService, objectMapper,
                minioObjectStorageService, minioProperties);
        MockMultipartFile file = new MockMultipartFile("file", "student.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", buildMinimalXlsxBytes());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.submitImport(file, "user-1"));

        assertTrue(exception.getMessage().contains("导入文件大小超过限制"));
        verify(taskCenterService, never()).createTask(any(CreateAsyncTaskCommand.class));
        verify(minioObjectStorageService, never()).uploadExcel(any(InputStream.class), anyLong(), anyString());
    }

    @Test
    void recoverMarksImportFailedWhenAutoRecoveryDisabled() {
        StudentService studentService = mock(StudentService.class);
        TaskCenterService taskCenterService = mock(TaskCenterService.class);
        MinioObjectStorageService minioObjectStorageService = mock(MinioObjectStorageService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportTempDir(tempDir.toString());
        properties.setImportAutoRecoveryEnabled(false);
        MinioProperties minioProperties = new MinioProperties();
        StudentImportTaskServiceImpl service = new StudentImportTaskServiceImpl(
                studentService, properties, new DirectThreadPoolTaskExecutor(), taskCenterService, objectMapper,
                minioObjectStorageService, minioProperties);
        AsyncTaskRecord task = AsyncTaskRecord.builder()
                .taskId("task-recover")
                .taskType(AsyncTaskType.IMPORT.name())
                .status(AsyncTaskStatus.RUNNING.name())
                .build();

        service.recover(task);

        verify(taskCenterService).markFailed("task-recover", "导入任务执行节点异常退出，请重新提交导入任务");
        verify(studentService, never()).importExcel(any(InputStream.class), anyInt(), any(StudentImportProgressCallback.class));
    }

    @Test
    void submitImportClampsBatchSize() throws Exception {
        StudentService studentService = mock(StudentService.class);
        TaskCenterService taskCenterService = mock(TaskCenterService.class);
        MinioObjectStorageService minioObjectStorageService = mock(MinioObjectStorageService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportTempDir(tempDir.toString());
        properties.setImportBatchSize(999999);
        MinioProperties minioProperties = new MinioProperties();
        minioProperties.setImportErrorObjectPrefix("excel/student/import-error");
        minioProperties.setImportSourceObjectPrefix("excel/student/import-source");

        when(taskCenterService.createTask(any(CreateAsyncTaskCommand.class))).thenAnswer(invocation -> {
            CreateAsyncTaskCommand command = invocation.getArgument(0);
            return AsyncTaskRecord.builder()
                    .taskId("task-3")
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

        StudentImportTaskServiceImpl service = new StudentImportTaskServiceImpl(
                studentService, properties, new NoopThreadPoolTaskExecutor(), taskCenterService, objectMapper,
                minioObjectStorageService, minioProperties);
        MockMultipartFile file = new MockMultipartFile("file", "student.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", buildMinimalXlsxBytes());

        service.submitImport(file, "user-1");

        ArgumentCaptor<CreateAsyncTaskCommand> createCaptor = ArgumentCaptor.forClass(CreateAsyncTaskCommand.class);
        verify(taskCenterService).createTask(createCaptor.capture());
        assertTrue(createCaptor.getValue().getRequestPayload().contains("\"batchSize\":5000"));
    }

    private byte[] buildMinimalXlsxBytes() throws Exception {
        return buildXlsxWithWorksheetRows(1);
    }

    private byte[] buildXlsxWithWorksheetRows(int rowCount) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            writeZipEntry(zipOutputStream, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"></Types>");
            writeZipEntry(zipOutputStream, "xl/workbook.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"></workbook>");
            StringBuilder worksheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet><sheetData>");
            for (int i = 1; i <= rowCount; i++) {
                worksheet.append("<row r=\"").append(i).append("\"></row>");
            }
            worksheet.append("</sheetData></worksheet>");
            writeZipEntry(zipOutputStream, "xl/worksheets/sheet1.xml", worksheet.toString());
        }
        return outputStream.toByteArray();
    }

    private void writeZipEntry(ZipOutputStream zipOutputStream, String entryName, String content) throws Exception {
        zipOutputStream.putNextEntry(new ZipEntry(entryName));
        zipOutputStream.write(content.getBytes("UTF-8"));
        zipOutputStream.closeEntry();
    }

    private static class DirectThreadPoolTaskExecutor extends ThreadPoolTaskExecutor {

        @Override
        public void execute(Runnable task) {
            task.run();
        }
    }

    private static class NoopThreadPoolTaskExecutor extends ThreadPoolTaskExecutor {

        @Override
        public void execute(Runnable task) {
        }
    }
}
