package com.huang.demo.excel.service.impl;

import com.alibaba.excel.EasyExcel;
import com.huang.demo.excel.config.ExcelDemoProperties;
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

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
            verify(studentMapper, never()).mergeImportStageRangeToStudent(anyString(), anyInt(), anyInt());
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
        when(studentMapper.mergeImportStageRangeToStudent(anyString(), anyInt(), anyInt()))
                .thenReturn(2);

        ThreadPoolTaskExecutor executor = newImportWorkerExecutor();
        try {
            StudentServiceImpl studentService = new StudentServiceImpl(
                    studentMapper,
                    properties,
                    new ImmediateTransactionManager(),
                    executor);

            studentService.importExcel(new ByteArrayInputStream(buildStudentExcel(5)), 2);

            verify(studentMapper, times(3)).mergeImportStageRangeToStudent(anyString(), anyInt(), anyInt());
            verify(studentMapper).mergeImportStageRangeToStudent(anyString(), eq(1), eq(2));
            verify(studentMapper).mergeImportStageRangeToStudent(anyString(), eq(3), eq(4));
            verify(studentMapper).mergeImportStageRangeToStudent(anyString(), eq(5), eq(5));
            verify(studentMapper, never()).mergeImportStageToStudent(anyString());
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
}
