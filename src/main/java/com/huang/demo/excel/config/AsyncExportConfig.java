package com.huang.demo.excel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling
public class AsyncExportConfig {

    private final ExcelDemoProperties properties;

    public AsyncExportConfig(ExcelDemoProperties properties) {
        this.properties = properties;
    }

    @Bean("exportTaskExecutor")
    public Executor exportTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int corePoolSize = Math.max(1, properties.getExportCorePoolSize());
        int maxPoolSize = Math.max(corePoolSize, properties.getExportMaxPoolSize());
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(Math.max(0, properties.getExportQueueCapacity()));
        executor.setRejectedExecutionHandler(buildRejectedExecutionHandler());
        executor.setThreadNamePrefix("student-export-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(1, properties.getExportAwaitTerminationSeconds()));
        executor.initialize();
        return executor;
    }

    @Bean("importWorkerExecutor")
    public ThreadPoolTaskExecutor importWorkerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int workerCount = Math.max(1, properties.getImportWorkerCount());
        executor.setCorePoolSize(workerCount);
        executor.setMaxPoolSize(workerCount);
        executor.setQueueCapacity(Math.max(0, properties.getImportExecutorQueueCapacity()));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setThreadNamePrefix("student-import-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(1, properties.getImportAwaitTerminationSeconds()));
        executor.initialize();
        return executor;
    }

    private RejectedExecutionHandler buildRejectedExecutionHandler() {
        String policy = properties.getExportRejectedExecutionPolicy();
        if ("caller-runs".equalsIgnoreCase(policy)) {
            return new ThreadPoolExecutor.CallerRunsPolicy();
        }
        return new ThreadPoolExecutor.AbortPolicy();
    }
}
