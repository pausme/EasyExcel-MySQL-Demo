package com.huang.demo.excel.config;

import com.huang.demo.task.monitor.TaskMetricsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncExportConfigTest {

    @Test
    void importWorkerExecutorScalesWithMaxConcurrentTasks() {
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportWorkerCount(4);
        properties.setImportMaxConcurrentTasks(2);

        ThreadPoolTaskExecutor executor = new AsyncExportConfig(properties, hikariEnvironment(10)).importWorkerExecutor();
        try {
            assertEquals(8, executor.getCorePoolSize());
            assertEquals(8, executor.getMaxPoolSize());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void importWorkerExecutorRejectsCapacityLargerThanDatabasePool() {
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportWorkerCount(4);
        properties.setImportMaxConcurrentTasks(4);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new AsyncExportConfig(properties, hikariEnvironment(10)).importWorkerExecutor());

        assertTrue(exception.getMessage().contains("导入写库线程总数不能超过数据库连接池最大连接数"));
    }

    @Test
    void importWorkerExecutorRequiresDatabasePoolSize() {
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportWorkerCount(1);
        properties.setImportMaxConcurrentTasks(1);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new AsyncExportConfig(properties, new MockEnvironment()).importWorkerExecutor());

        assertTrue(exception.getMessage().contains("缺少数据库连接池最大连接数配置"));
    }

    @Test
    void observableRejectPolicyRecordsMetric() throws Exception {
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setExportCorePoolSize(1);
        properties.setExportMaxPoolSize(1);
        properties.setExportQueueCapacity(0);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ThreadPoolTaskExecutor executor = new AsyncExportConfig(
                properties, hikariEnvironment(10), new TaskMetricsService(meterRegistry)).exportTaskExecutor();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                running.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(running.await(2, TimeUnit.SECONDS));

            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {
            }));

            assertEquals(1.0D, meterRegistry.get("demo.thread.pool.rejected.total")
                    .tag("pool", "student-export").counter().count());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    private MockEnvironment hikariEnvironment(int maximumPoolSize) {
        return new MockEnvironment()
                .withProperty("spring.datasource.hikari.maximum-pool-size", String.valueOf(maximumPoolSize));
    }
}
