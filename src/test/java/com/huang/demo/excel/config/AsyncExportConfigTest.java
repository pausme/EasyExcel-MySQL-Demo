package com.huang.demo.excel.config;

import org.junit.jupiter.api.Test;
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

        ThreadPoolTaskExecutor executor = new AsyncExportConfig(properties, null).importWorkerExecutor();
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
                () -> new AsyncExportConfig(properties, null).importWorkerExecutor());

        assertTrue(exception.getMessage().contains("导入写库线程总数不能超过数据库连接池最大连接数"));
    }
}
