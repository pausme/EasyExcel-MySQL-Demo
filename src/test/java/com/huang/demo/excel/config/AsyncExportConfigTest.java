package com.huang.demo.excel.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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

    private MockEnvironment hikariEnvironment(int maximumPoolSize) {
        return new MockEnvironment()
                .withProperty("spring.datasource.hikari.maximum-pool-size", String.valueOf(maximumPoolSize));
    }
}
