package com.huang.demo.excel.service.impl;

import com.alibaba.excel.EasyExcel;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.domain.model.StudentImportMode;
import com.huang.demo.excel.domain.model.StudentImportProgressCallback;
import com.huang.demo.excel.domain.model.StudentImportResult;
import com.huang.demo.excel.domain.model.StudentImportStageRecord;
import com.huang.demo.excel.domain.model.StudentImportValidationException;
import com.huang.demo.excel.model.StudentExcelRow;
import com.huang.demo.excel.repository.StudentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentServiceImplTest {

    @Test
    void importExcelReleasesPermitWhenWorkerSubmissionFails() {
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportMaxConcurrentTasks(1);
        properties.setImportWorkerCount(1);
        properties.setImportQueueCapacity(1);
        StudentServiceImpl studentService = new StudentServiceImpl(
                mock(StudentMapper.class),
                properties,
                mock(PlatformTransactionManager.class),
                new RejectingTaskExecutor());

        IllegalStateException firstException = assertThrows(
                IllegalStateException.class,
                () -> studentService.importExcel(new ByteArrayInputStream(new byte[0]), 2000));
        IllegalStateException secondException = assertThrows(
                IllegalStateException.class,
                () -> studentService.importExcel(new ByteArrayInputStream(new byte[0]), 2000));

        assertTrue(firstException.getMessage().contains("导入写库线程池繁忙"));
        assertTrue(secondException.getMessage().contains("导入写库线程池繁忙"));
    }

    @Test
    void importExcelDoesNotWaitForeverWhenPartialWorkerSubmissionFails() {
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportMaxConcurrentTasks(1);
        properties.setImportWorkerCount(2);
        properties.setImportQueueCapacity(2);
        StudentServiceImpl studentService = new StudentServiceImpl(
                mock(StudentMapper.class),
                properties,
                mock(PlatformTransactionManager.class),
                new PartialRejectingTaskExecutor());

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            IllegalStateException firstException = assertThrows(
                    IllegalStateException.class,
                    () -> studentService.importExcel(new ByteArrayInputStream(new byte[0]), 2000));
            IllegalStateException secondException = assertThrows(
                    IllegalStateException.class,
                    () -> studentService.importExcel(new ByteArrayInputStream(new byte[0]), 2000));

            assertTrue(firstException.getMessage().contains("导入写库线程池繁忙"));
            assertTrue(secondException.getMessage().contains("导入写库线程池繁忙"));
        });
    }

    @Test
    void initRejectsWorkerFinishWaitShorterThanTransactionRetryWindow() {
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setInitEnabled(false);
        properties.setImportWorkerFinishWaitSeconds(30);
        properties.setImportTransactionTimeoutSeconds(60);
        properties.setImportMaxRetryTimes(3);
        StudentServiceImpl studentService = new StudentServiceImpl(
                mock(StudentMapper.class),
                properties,
                mock(PlatformTransactionManager.class),
                mock(ThreadPoolTaskExecutor.class));

        IllegalStateException exception = assertThrows(IllegalStateException.class, studentService::init);

        assertTrue(exception.getMessage().contains("IMPORT_WORKER_FINISH_WAIT_SECONDS"));
    }

    @Test
    void initRejectsNonPositiveImportTransactionTimeout() {
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setInitEnabled(false);
        properties.setImportTransactionTimeoutSeconds(0);
        StudentServiceImpl studentService = new StudentServiceImpl(
                mock(StudentMapper.class),
                properties,
                mock(PlatformTransactionManager.class),
                mock(ThreadPoolTaskExecutor.class));

        IllegalStateException exception = assertThrows(IllegalStateException.class, studentService::init);

        assertTrue(exception.getMessage().contains("IMPORT_TRANSACTION_TIMEOUT_SECONDS"));
    }

    @Test
    void importExcelDoesNotMergeWhenStageHasDuplicateStudentNo() {
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportMaxConcurrentTasks(1);
        properties.setImportWorkerCount(1);
        properties.setImportQueueCapacity(2);
        properties.setImportBatchSize(2);
        properties.setInsertBatchSize(2);
        properties.setImportWorkerFinishWaitSeconds(5);
        StudentMapper studentMapper = mock(StudentMapper.class);
        List<StudentImportStageRecord> stagedRows = new CopyOnWriteArrayList<StudentImportStageRecord>();
        doAnswer(invocation -> {
            List<StudentImportStageRecord> rows = invocation.getArgument(0);
            stagedRows.addAll(rows);
            return null;
        }).when(studentMapper).saveImportStageBatch(any());
        when(studentMapper.countImportStageRows(anyString())).thenAnswer(invocation -> stagedRows.size());
        when(studentMapper.countDuplicateImportStageStudentNo(anyString())).thenReturn(1);
        when(studentMapper.listInvalidImportStageRows(anyString())).thenReturn(Collections.emptyList());
        when(studentMapper.listDuplicateImportStageStudentNoRows(anyString())).thenAnswer(invocation -> stagedRows);

        ThreadPoolTaskExecutor executor = newImportWorkerExecutor();
        try {
            StudentServiceImpl studentService = new StudentServiceImpl(
                    studentMapper,
                    properties,
                    new ImmediateTransactionManager(),
                    executor);

            StudentImportValidationException exception = assertThrows(
                    StudentImportValidationException.class,
                    () -> studentService.importExcel(new ByteArrayInputStream(buildDuplicateStudentExcel()), 2));

            assertTrue(exception.getErrorRows().get(0).getErrorMessage().contains("重复"));
            verify(studentMapper, never()).mergeImportStageToStudent(anyString());
            verify(studentMapper, never()).mergeImportStageRangeToStudent(anyString(), anyInt(), anyInt(), anyLong());
            verify(studentMapper).deleteImportStage(anyString());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void importExcelTreatsBlankStudentNoAsValidationError() {
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportMaxConcurrentTasks(1);
        properties.setImportWorkerCount(1);
        properties.setImportQueueCapacity(2);
        properties.setImportBatchSize(2);
        properties.setInsertBatchSize(2);
        properties.setImportWorkerFinishWaitSeconds(5);
        StudentMapper studentMapper = mock(StudentMapper.class);
        List<StudentImportStageRecord> stagedRows = new CopyOnWriteArrayList<StudentImportStageRecord>();
        doAnswer(invocation -> {
            List<StudentImportStageRecord> rows = invocation.getArgument(0);
            stagedRows.addAll(rows);
            return null;
        }).when(studentMapper).saveImportStageBatch(any());
        when(studentMapper.countImportStageRows(anyString())).thenAnswer(invocation -> stagedRows.size());
        when(studentMapper.listInvalidImportStageRows(anyString())).thenAnswer(invocation -> stagedRows);
        when(studentMapper.listDuplicateImportStageStudentNoRows(anyString())).thenReturn(Collections.emptyList());

        ThreadPoolTaskExecutor executor = newImportWorkerExecutor();
        try {
            StudentServiceImpl studentService = new StudentServiceImpl(
                    studentMapper,
                    properties,
                    new ImmediateTransactionManager(),
                    executor);

            StudentImportValidationException exception = assertThrows(
                    StudentImportValidationException.class,
                    () -> studentService.importExcel(new ByteArrayInputStream(buildBlankStudentNoExcel()), 2));

            assertTrue(exception.getMessage().contains("errorRows=1"));
            assertEquals(1, exception.getErrorRows().size());
            assertTrue(exception.getErrorRows().get(0).getErrorMessage().contains("学号不能为空"));
            assertTrue(exception.getErrorRows().get(0).getErrorMessage().contains("姓名不能为空"));
            verify(studentMapper, never()).mergeImportStageToStudent(anyString());
            verify(studentMapper, never()).mergeImportStageRangeToStudent(anyString(), anyInt(), anyInt(), anyLong());
            verify(studentMapper).deleteImportStage(anyString());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void importExcelMergesStageByConfiguredChunks() {
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportMaxConcurrentTasks(1);
        properties.setImportWorkerCount(1);
        properties.setImportQueueCapacity(2);
        properties.setImportBatchSize(2);
        properties.setInsertBatchSize(2);
        properties.setImportMergeChunkSize(2);
        properties.setImportWorkerFinishWaitSeconds(5);
        StudentMapper studentMapper = mock(StudentMapper.class);
        List<StudentImportStageRecord> stagedRows = new CopyOnWriteArrayList<StudentImportStageRecord>();
        doAnswer(invocation -> {
            List<StudentImportStageRecord> rows = invocation.getArgument(0);
            stagedRows.addAll(rows);
            return null;
        }).when(studentMapper).saveImportStageBatch(any());
        when(studentMapper.countImportStageRows(anyString())).thenAnswer(invocation -> stagedRows.size());
        when(studentMapper.listInvalidImportStageRows(anyString())).thenReturn(Collections.emptyList());
        when(studentMapper.listDuplicateImportStageStudentNoRows(anyString())).thenReturn(Collections.emptyList());
        when(studentMapper.currentStudentVersion()).thenReturn(100L);
        when(studentMapper.mergeImportStageRangeToStudent(anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(2);
        when(studentMapper.promoteStudentVersion(eq(100L), anyLong())).thenReturn(1);

        ThreadPoolTaskExecutor executor = newImportWorkerExecutor();
        try {
            StudentServiceImpl studentService = new StudentServiceImpl(
                    studentMapper,
                    properties,
                    new ImmediateTransactionManager(),
                    executor);

            studentService.importExcel(new ByteArrayInputStream(buildStudentExcel(5)), 2);

            verify(studentMapper, times(3)).mergeImportStageRangeToStudent(anyString(), anyInt(), anyInt(), anyLong());
            verify(studentMapper).mergeImportStageRangeToStudent(anyString(), eq(1), eq(2), anyLong());
            verify(studentMapper).mergeImportStageRangeToStudent(anyString(), eq(3), eq(4), anyLong());
            verify(studentMapper).mergeImportStageRangeToStudent(anyString(), eq(5), eq(5), anyLong());
            verify(studentMapper).promoteStudentVersion(eq(100L), anyLong());
            verify(studentMapper, never()).mergeImportStageToStudent(anyString());
            verify(studentMapper).deleteImportStage(anyString());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void importExcelAppendModeMergesIntoCurrentVersionWithoutPromotion() {
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportMaxConcurrentTasks(1);
        properties.setImportWorkerCount(1);
        properties.setImportQueueCapacity(2);
        properties.setImportBatchSize(2);
        properties.setInsertBatchSize(2);
        properties.setImportMergeChunkSize(2);
        properties.setImportWorkerFinishWaitSeconds(5);
        StudentMapper studentMapper = mock(StudentMapper.class);
        List<StudentImportStageRecord> stagedRows = new CopyOnWriteArrayList<StudentImportStageRecord>();
        doAnswer(invocation -> {
            List<StudentImportStageRecord> rows = invocation.getArgument(0);
            stagedRows.addAll(rows);
            return null;
        }).when(studentMapper).saveImportStageBatch(any());
        when(studentMapper.countImportStageRows(anyString())).thenAnswer(invocation -> stagedRows.size());
        when(studentMapper.listInvalidImportStageRows(anyString())).thenReturn(Collections.emptyList());
        when(studentMapper.listDuplicateImportStageStudentNoRows(anyString())).thenReturn(Collections.emptyList());
        when(studentMapper.mergeImportStageRangeToCurrentStudent(anyString(), anyInt(), anyInt())).thenReturn(2);

        ThreadPoolTaskExecutor executor = newImportWorkerExecutor();
        try {
            StudentServiceImpl studentService = new StudentServiceImpl(
                    studentMapper,
                    properties,
                    new ImmediateTransactionManager(),
                    executor);

            StudentImportResult result = studentService.importExcel(
                    new ByteArrayInputStream(buildStudentExcel(5)), 2, StudentImportMode.APPEND,
                    StudentImportProgressCallback.NONE);

            assertEquals(5, result.getImportedCount());
            assertEquals(5, result.getValidatedCount());
            assertEquals(StudentImportMode.APPEND, result.getImportMode());
            verify(studentMapper, times(3)).mergeImportStageRangeToCurrentStudent(anyString(), anyInt(), anyInt());
            verify(studentMapper).mergeImportStageRangeToCurrentStudent(anyString(), eq(1), eq(2));
            verify(studentMapper).mergeImportStageRangeToCurrentStudent(anyString(), eq(3), eq(4));
            verify(studentMapper).mergeImportStageRangeToCurrentStudent(anyString(), eq(5), eq(5));
            verify(studentMapper, never()).promoteStudentVersion(anyLong(), anyLong());
            verify(studentMapper, never()).mergeImportStageRangeToStudent(anyString(), anyInt(), anyInt(), anyLong());
            verify(studentMapper).deleteImportStage(anyString());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void importExcelValidateOnlyModeDoesNotMergeIntoStudentRecord() {
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportMaxConcurrentTasks(1);
        properties.setImportWorkerCount(1);
        properties.setImportQueueCapacity(2);
        properties.setImportBatchSize(2);
        properties.setInsertBatchSize(2);
        properties.setImportWorkerFinishWaitSeconds(5);
        StudentMapper studentMapper = mock(StudentMapper.class);
        List<StudentImportStageRecord> stagedRows = new CopyOnWriteArrayList<StudentImportStageRecord>();
        doAnswer(invocation -> {
            List<StudentImportStageRecord> rows = invocation.getArgument(0);
            stagedRows.addAll(rows);
            return null;
        }).when(studentMapper).saveImportStageBatch(any());
        when(studentMapper.countImportStageRows(anyString())).thenAnswer(invocation -> stagedRows.size());
        when(studentMapper.listInvalidImportStageRows(anyString())).thenReturn(Collections.emptyList());
        when(studentMapper.listDuplicateImportStageStudentNoRows(anyString())).thenReturn(Collections.emptyList());

        ThreadPoolTaskExecutor executor = newImportWorkerExecutor();
        try {
            StudentServiceImpl studentService = new StudentServiceImpl(
                    studentMapper,
                    properties,
                    new ImmediateTransactionManager(),
                    executor);

            StudentImportResult result = studentService.importExcel(
                    new ByteArrayInputStream(buildStudentExcel(2)), 2, StudentImportMode.VALIDATE_ONLY,
                    StudentImportProgressCallback.NONE);

            assertEquals(0, result.getImportedCount());
            assertEquals(2, result.getValidatedCount());
            assertEquals(StudentImportMode.VALIDATE_ONLY, result.getImportMode());
            verify(studentMapper, never()).mergeImportStageToStudent(anyString());
            verify(studentMapper, never()).mergeImportStageRangeToStudent(anyString(), anyInt(), anyInt(), anyLong());
            verify(studentMapper, never()).mergeImportStageRangeToCurrentStudent(anyString(), anyInt(), anyInt());
            verify(studentMapper, never()).promoteStudentVersion(anyLong(), anyLong());
            verify(studentMapper).deleteImportStage(anyString());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void importExcelDeletesUnpublishedRowsWhenVersionPromotionFails() {
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportMaxConcurrentTasks(1);
        properties.setImportWorkerCount(1);
        properties.setImportQueueCapacity(2);
        properties.setImportBatchSize(2);
        properties.setInsertBatchSize(2);
        properties.setImportMergeChunkSize(2);
        properties.setImportWorkerFinishWaitSeconds(5);
        StudentMapper studentMapper = mock(StudentMapper.class);
        List<StudentImportStageRecord> stagedRows = new CopyOnWriteArrayList<StudentImportStageRecord>();
        doAnswer(invocation -> {
            List<StudentImportStageRecord> rows = invocation.getArgument(0);
            stagedRows.addAll(rows);
            return null;
        }).when(studentMapper).saveImportStageBatch(any());
        when(studentMapper.countImportStageRows(anyString())).thenAnswer(invocation -> stagedRows.size());
        when(studentMapper.listInvalidImportStageRows(anyString())).thenReturn(Collections.emptyList());
        when(studentMapper.listDuplicateImportStageStudentNoRows(anyString())).thenReturn(Collections.emptyList());
        when(studentMapper.currentStudentVersion()).thenReturn(100L);
        when(studentMapper.mergeImportStageRangeToStudent(anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(2);
        when(studentMapper.promoteStudentVersion(eq(100L), anyLong())).thenReturn(0);
        when(studentMapper.deleteStudentRowsByImportTaskId(anyString())).thenReturn(2);

        ThreadPoolTaskExecutor executor = newImportWorkerExecutor();
        try {
            StudentServiceImpl studentService = new StudentServiceImpl(
                    studentMapper,
                    properties,
                    new ImmediateTransactionManager(),
                    executor);

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> studentService.importExcel(new ByteArrayInputStream(buildStudentExcel(2)), 2));

            assertTrue(exception.getMessage().contains("导入版本发布失败"));
            verify(studentMapper).deleteStudentRowsByImportTaskId(anyString());
            verify(studentMapper).deleteImportStage(anyString());
        } finally {
            executor.shutdown();
        }
    }

    private static class RejectingTaskExecutor extends ThreadPoolTaskExecutor {

        @Override
        public Future<?> submit(Runnable task) {
            throw new RejectedExecutionException("test rejection");
        }
    }

    private static class PartialRejectingTaskExecutor extends ThreadPoolTaskExecutor {

        private final AtomicInteger submitCount = new AtomicInteger();

        @Override
        public Future<?> submit(Runnable task) {
            if (submitCount.incrementAndGet() == 1) {
                return new FutureTask<Void>(() -> null);
            }
            throw new RejectedExecutionException("test rejection");
        }
    }

    private ThreadPoolTaskExecutor newImportWorkerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        return executor;
    }

    private byte[] buildDuplicateStudentExcel() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        EasyExcel.write(outputStream, StudentExcelRow.class)
                .sheet("学生数据")
                .doWrite(Arrays.asList(
                        StudentExcelRow.builder()
                                .studentNo("S001")
                                .name("张三")
                                .age(18)
                                .gender("男")
                                .className("一班")
                                .email("s001@example.com")
                                .birthday("2000-01-01")
                                .build(),
                        StudentExcelRow.builder()
                                .studentNo("S001")
                                .name("李四")
                                .age(19)
                                .gender("女")
                                .className("二班")
                                .email("s001-b@example.com")
                                .birthday("2000-01-02")
                        .build()));
        return outputStream.toByteArray();
    }

    private byte[] buildBlankStudentNoExcel() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        EasyExcel.write(outputStream, StudentExcelRow.class)
                .sheet("学生数据")
                .doWrite(Collections.singletonList(
                        StudentExcelRow.builder()
                                .studentNo("")
                                .name("")
                                .age(18)
                                .gender("男")
                                .className("一班")
                                .email("student@example.com")
                                .birthday("2000-01-01")
                                .build()));
        return outputStream.toByteArray();
    }

    private byte[] buildStudentExcel(int rows) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        List<StudentExcelRow> data = new java.util.ArrayList<StudentExcelRow>(rows);
        for (int i = 1; i <= rows; i++) {
            data.add(StudentExcelRow.builder()
                    .studentNo("S" + String.format("%03d", i))
                    .name("学生" + i)
                    .age(18 + i)
                    .gender(i % 2 == 0 ? "女" : "男")
                    .className("一班")
                    .email("s" + i + "@example.com")
                    .birthday("2000-01-01")
                    .build());
        }
        EasyExcel.write(outputStream, StudentExcelRow.class)
                .sheet("学生数据")
                .doWrite(data);
        return outputStream.toByteArray();
    }

    private static class ImmediateTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }

    @org.junit.jupiter.api.Test
    void appendFailureTriggersBackupRollback() throws Exception {
        // QA-06：APPEND 合并失败 → 恢复备份 → 删除本任务新增行 → 清理备份 → 重抛
        StudentMapper studentMapper = mock(StudentMapper.class);
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportMergeChunkSize(5000);
        StudentServiceImpl studentService = new StudentServiceImpl(
                studentMapper, properties, mock(PlatformTransactionManager.class), new RejectingTaskExecutor());
        org.mockito.Mockito.doThrow(new RuntimeException("merge chunk failed"))
                .when(studentMapper).mergeImportStageRangeToCurrentStudent(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt());
        java.lang.reflect.Method method = StudentServiceImpl.class
                .getDeclaredMethod("appendImportStageToCurrentVersion", String.class, int.class,
                        com.huang.demo.excel.domain.model.StudentImportProgressCallback.class);
        method.setAccessible(true);
        com.huang.demo.excel.domain.model.StudentImportProgressCallback callback =
                org.mockito.Mockito.mock(com.huang.demo.excel.domain.model.StudentImportProgressCallback.class);
        try {
            method.invoke(studentService, "task-rb", 2000, callback);
            org.junit.jupiter.api.Assertions.fail("应抛出合并异常");
        } catch (java.lang.reflect.InvocationTargetException ex) {
            org.junit.jupiter.api.Assertions.assertEquals("merge chunk failed", ex.getCause().getMessage());
        }
        org.mockito.Mockito.verify(studentMapper).createAppendBackupTableIfAbsent();
        org.mockito.Mockito.verify(studentMapper).backupCurrentRowsForStage("task-rb");
        org.mockito.Mockito.verify(studentMapper).restoreAppendBackup("task-rb");
        org.mockito.Mockito.verify(studentMapper).deleteInsertedRowsByTaskInCurrentVersion("task-rb");
        org.mockito.Mockito.verify(studentMapper).deleteAppendBackup("task-rb");
    }
}
