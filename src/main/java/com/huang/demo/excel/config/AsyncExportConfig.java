package com.huang.demo.excel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling
public class AsyncExportConfig {

    private final ExcelDemoProperties properties;
    private final Environment environment;

    public AsyncExportConfig(ExcelDemoProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Bean("exportTaskExecutor")
    public ThreadPoolTaskExecutor exportTaskExecutor() {
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
        int maxConcurrentTasks = Math.max(1, properties.getImportMaxConcurrentTasks());
        int totalWorkerCapacity = workerCount * maxConcurrentTasks;
        validateImportWorkerCapacity(totalWorkerCapacity);
        executor.setCorePoolSize(totalWorkerCapacity);
        executor.setMaxPoolSize(totalWorkerCapacity);
        executor.setQueueCapacity(Math.max(0, properties.getImportExecutorQueueCapacity()));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setThreadNamePrefix("student-import-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(1, properties.getImportAwaitTerminationSeconds()));
        executor.initialize();
        return executor;
    }

    @Bean("importTaskExecutor")
    public ThreadPoolTaskExecutor importTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int corePoolSize = Math.max(1, properties.getImportTaskCorePoolSize());
        int maxPoolSize = Math.max(corePoolSize, properties.getImportTaskMaxPoolSize());
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(Math.max(0, properties.getImportTaskQueueCapacity()));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setThreadNamePrefix("student-import-task-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(1, properties.getImportAwaitTerminationSeconds()));
        executor.initialize();
        return executor;
    }

    private void validateImportWorkerCapacity(int totalWorkerCapacity) {
        int maximumPoolSize = getDataSourceMaximumPoolSize();
        if (totalWorkerCapacity > maximumPoolSize) {
            throw new IllegalStateException(
                    "导入写库线程总数不能超过数据库连接池最大连接数，totalWorkerCapacity="
                            + totalWorkerCapacity + ", hikariMaximumPoolSize=" + maximumPoolSize);
        }
    }

    private int getDataSourceMaximumPoolSize() {
        if (environment == null) {
            throw new IllegalStateException("缺少数据库连接池最大连接数配置 spring.datasource.hikari.maximum-pool-size");
        }
        Integer maximumPoolSize = environment.getProperty(
                "spring.datasource.hikari.maximum-pool-size", Integer.class);
        if (maximumPoolSize == null) {
            throw new IllegalStateException("缺少数据库连接池最大连接数配置 spring.datasource.hikari.maximum-pool-size");
        }
        return Math.max(1, maximumPoolSize);
    }

    private RejectedExecutionHandler buildRejectedExecutionHandler() {
        String policy = properties.getExportRejectedExecutionPolicy();
        if ("caller-runs".equalsIgnoreCase(policy)) {
            return new ThreadPoolExecutor.CallerRunsPolicy();
        }
        return new ThreadPoolExecutor.AbortPolicy();
    }
}
