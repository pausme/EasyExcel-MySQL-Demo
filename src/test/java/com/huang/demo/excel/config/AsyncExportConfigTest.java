package com.huang.demo.excel.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsyncExportConfigTest {

    @Test
    void importWorkerExecutorScalesWithMaxConcurrentTasks() {
        ExcelDemoProperties properties = new ExcelDemoProperties();
        properties.setImportWorkerCount(4);
        properties.setImportMaxConcurrentTasks(2);

        ThreadPoolTaskExecutor executor = new AsyncExportConfig(properties).importWorkerExecutor();
        try {
            assertEquals(8, executor.getCorePoolSize());
            assertEquals(8, executor.getMaxPoolSize());
        } finally {
            executor.shutdown();
        }
    }
}
