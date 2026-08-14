package com.huang.demo.task.monitor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.annotation.PostConstruct;
import java.util.Collections;

@Configuration
public class ThreadPoolMetricsConfig {

    private final MeterRegistry meterRegistry;
    private final ThreadPoolTaskExecutor exportTaskExecutor;
    private final ThreadPoolTaskExecutor importTaskExecutor;
    private final ThreadPoolTaskExecutor importWorkerExecutor;

    public ThreadPoolMetricsConfig(MeterRegistry meterRegistry,
                                   @Qualifier("exportTaskExecutor") ThreadPoolTaskExecutor exportTaskExecutor,
                                   @Qualifier("importTaskExecutor") ThreadPoolTaskExecutor importTaskExecutor,
                                   @Qualifier("importWorkerExecutor") ThreadPoolTaskExecutor importWorkerExecutor) {
        this.meterRegistry = meterRegistry;
        this.exportTaskExecutor = exportTaskExecutor;
        this.importTaskExecutor = importTaskExecutor;
        this.importWorkerExecutor = importWorkerExecutor;
    }

    @PostConstruct
    public void bindThreadPools() {
        bind("student-export", exportTaskExecutor);
        bind("student-import-task", importTaskExecutor);
        bind("student-import-worker", importWorkerExecutor);
    }

    private void bind(String name, ThreadPoolTaskExecutor executor) {
        ExecutorServiceMetrics.monitor(meterRegistry, executor.getThreadPoolExecutor(), name, Collections.emptyList());
    }
}
