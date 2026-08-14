package com.huang.demo.task.monitor;

import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class TaskMetricsService {

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<String, Counter>();

    public TaskMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordSubmitted(AsyncTaskRecord record) {
        increment("submitted", record);
    }

    public void recordStatusChanged(AsyncTaskRecord record) {
        increment(record.getStatus().toLowerCase(), record);
        recordDuration(record);
    }

    private void increment(String outcome, AsyncTaskRecord record) {
        if (record == null) {
            return;
        }
        String taskType = record.getTaskType() == null ? "UNKNOWN" : record.getTaskType();
        String key = taskType + ":" + outcome;
        counters.computeIfAbsent(key, ignored -> Counter.builder("demo.async.task.total")
                        .description("Async task status transition count")
                        .tags(Tags.of("taskType", taskType, "outcome", outcome))
                        .register(meterRegistry))
                .increment();
    }

    private void recordDuration(AsyncTaskRecord record) {
        if (record.getStartedAt() == null || record.getFinishedAt() == null) {
            return;
        }
        long millis = Duration.between(record.getStartedAt(), record.getFinishedAt()).toMillis();
        if (millis < 0L) {
            return;
        }
        Timer.builder("demo.async.task.duration")
                .description("Async task execution duration")
                .tags("taskType", record.getTaskType(), "status", record.getStatus())
                .register(meterRegistry)
                .record(Duration.ofMillis(millis));
    }
}
