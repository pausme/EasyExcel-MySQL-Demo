package com.huang.demo.task.monitor;

import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
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

    public void recordThreadPoolRejected(String poolName) {
        Counter.builder("demo.thread.pool.rejected.total")
                .description("Thread pool rejected task count")
                .tag("pool", normalizeTag(poolName))
                .register(meterRegistry)
                .increment();
    }

    public void recordRowsProcessed(String scene, long rows, long elapsedMs) {
        if (rows < 0L || elapsedMs < 0L) {
            return;
        }
        String normalizedScene = normalizeTag(scene);
        Counter.builder("demo.excel.rows.total")
                .description("Excel rows processed")
                .tag("scene", normalizedScene)
                .register(meterRegistry)
                .increment(rows);
        Timer.builder("demo.excel.process.duration")
                .description("Excel import/export processing duration")
                .tag("scene", normalizedScene)
                .register(meterRegistry)
                .record(Duration.ofMillis(elapsedMs));
        if (elapsedMs > 0L) {
            double rowsPerSecond = rows * 1000.0D / elapsedMs;
            DistributionSummary.builder("demo.excel.row.rate")
                    .description("Excel row processing rate")
                    .tag("scene", normalizedScene)
                    .baseUnit("rows_per_second")
                    .register(meterRegistry)
                    .record(rowsPerSecond);
        }
    }

    public void recordStorageUpload(String scene, long elapsedMs, boolean success) {
        String normalizedScene = normalizeTag(scene);
        Timer.builder("demo.storage.upload.duration")
                .description("Storage upload duration")
                .tags("scene", normalizedScene, "success", String.valueOf(success))
                .register(meterRegistry)
                .record(Duration.ofMillis(Math.max(0L, elapsedMs)));
    }

    public void recordErrorFile(String outcome) {
        Counter.builder("demo.excel.error.file.total")
                .description("Import error file generation count")
                .tag("outcome", normalizeTag(outcome))
                .register(meterRegistry)
                .increment();
    }

    public void recordCompensationBacklog(String status, long count) {
        DistributionSummary.builder("demo.compensation.backlog")
                .description("Compensation backlog sample")
                .tag("status", normalizeTag(status))
                .baseUnit("records")
                .register(meterRegistry)
                .record(Math.max(0L, count));
    }

    public void recordCompensationAutoExecution(String outcome, String bizType, String failureType) {
        Counter.builder("demo.compensation.auto.execution.total")
                .description("Compensation auto execution result count")
                .tags("outcome", normalizeTag(outcome),
                        "bizType", normalizeTag(bizType),
                        "failureType", normalizeTag(failureType))
                .register(meterRegistry)
                .increment();
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

    private String normalizeTag(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "UNKNOWN";
        }
        String normalized = value.trim();
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }
}
