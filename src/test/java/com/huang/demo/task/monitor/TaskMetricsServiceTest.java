package com.huang.demo.task.monitor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TaskMetricsServiceTest {

    @Test
    void recordsBusinessAndRejectionMetrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TaskMetricsService service = new TaskMetricsService(meterRegistry);

        service.recordThreadPoolRejected("student-export");
        service.recordRowsProcessed("export", 1000L, 2000L);
        service.recordStorageUpload("export-result", 120L, true);
        service.recordErrorFile("success");
        service.recordCompensationBacklog("PENDING", 3L);

        assertEquals(1.0D, meterRegistry.get("demo.thread.pool.rejected.total")
                .tag("pool", "student-export").counter().count());
        assertEquals(1000.0D, meterRegistry.get("demo.excel.rows.total")
                .tag("scene", "export").counter().count());
        assertNotNull(meterRegistry.get("demo.excel.row.rate").tag("scene", "export").summary());
        assertEquals(1L, meterRegistry.get("demo.storage.upload.duration")
                .tag("scene", "export-result").tag("success", "true").timer().count());
        assertEquals(1.0D, meterRegistry.get("demo.excel.error.file.total")
                .tag("outcome", "success").counter().count());
        assertNotNull(meterRegistry.get("demo.compensation.backlog").tag("status", "PENDING").summary());
    }
}
