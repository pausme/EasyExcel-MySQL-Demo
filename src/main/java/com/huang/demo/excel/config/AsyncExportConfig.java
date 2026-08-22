package com.huang.demo.excel.config;

import com.huang.demo.task.monitor.TaskMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling
public class AsyncExportConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncExportConfig.class);

    private final ExcelDemoProperties properties;
    private final Environment environment;
    private final TaskMetricsService taskMetricsService;

    @Autowired
    public AsyncExportConfig(ExcelDemoProperties properties,
                             Environment environment,
                             TaskMetricsService taskMetricsService) {
        this.properties = properties;
        this.environment = environment;
        this.taskMetricsService = taskMetricsService;
    }

    public AsyncExportConfig(ExcelDemoProperties properties, Environment environment) {
        this(properties, environment, null);
    }

    @Bean("exportTaskExecutor")
    public ThreadPoolTaskExecutor exportTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int corePoolSize = Math.max(1, properties.getExportCorePoolSize());
        int maxPoolSize = Math.max(corePoolSize, properties.getExportMaxPoolSize());
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(Math.max(0, properties.getExportQueueCapacity()));
        executor.setRejectedExecutionHandler(buildRejectedExecutionHandler("student-export"));
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
        executor.setRejectedExecutionHandler(buildObservableAbortPolicy("student-import-worker"));
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
        executor.setRejectedExecutionHandler(buildObservableAbortPolicy("student-import-task"));
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

    private RejectedExecutionHandler buildRejectedExecutionHandler(String poolName) {
        String policy = properties.getExportRejectedExecutionPolicy();
        if ("caller-runs".equalsIgnoreCase(policy)) {
            return new ThreadPoolExecutor.CallerRunsPolicy();
        }
        return buildObservableAbortPolicy(poolName);
    }

    private RejectedExecutionHandler buildObservableAbortPolicy(String poolName) {
        return (runnable, executor) -> {
            int activeCount = executor == null ? -1 : executor.getActiveCount();
            int queueSize = executor == null || executor.getQueue() == null ? -1 : executor.getQueue().size();
            int queueRemaining = executor == null || executor.getQueue() == null
                    ? -1 : executor.getQueue().remainingCapacity();
            if (taskMetricsService != null) {
                taskMetricsService.recordThreadPoolRejected(poolName);
            }
            log.warn("thread pool rejected task, pool={}, activeCount={}, queueSize={}, queueRemaining={}",
                    poolName, activeCount, queueSize, queueRemaining);
            throw new RejectedExecutionException("线程池繁忙，pool=" + poolName);
        };
    }
}
