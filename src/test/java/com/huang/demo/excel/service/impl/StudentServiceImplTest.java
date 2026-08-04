package com.huang.demo.excel.service.impl;

import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.repository.StudentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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
}
