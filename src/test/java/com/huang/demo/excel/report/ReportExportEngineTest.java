package com.huang.demo.excel.report;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportExportEngineTest {

    @TempDir
    private Path tempDir;

    @Test
    void writeExportsRowsByCursorPages() throws Exception {
        ReportExportEngine engine = new ReportExportEngine();
        DemoReportJob job = new DemoReportJob(5);
        List<Integer> progressList = new ArrayList<Integer>();
        AtomicInteger cancelCheckCount = new AtomicInteger();
        Path filePath = tempDir.resolve("demo-report.xlsx");

        ReportExportResult result = engine.write(job, ReportExportCommand.<String>builder()
                .taskId("task-1")
                .params("demo")
                .snapshotMaxId(5L)
                .pageSize(2)
                .sheetRowLimit(10)
                .filePath(filePath)
                .cancelChecker(new ReportCancelChecker() {
                    @Override
                    public void checkCanceled() {
                        cancelCheckCount.incrementAndGet();
                    }
                })
                .progressUpdater(new ReportProgressUpdater() {
                    @Override
                    public void update(long completedCount, long totalCount, int progressPercent) {
                        progressList.add(progressPercent);
                    }
                })
                .build());

        assertEquals(5L, result.getTotal());
        assertEquals(5L, result.getExported());
        assertEquals(1, result.getSheetCount());
        assertTrue(Files.size(filePath) > 0L);
        assertEquals(3, job.getQueryCount());
        assertTrue(cancelCheckCount.get() >= 3);
        assertTrue(progressList.contains(99));
    }

    @Test
    void writeCreatesHeaderWhenReportIsEmpty() throws Exception {
        ReportExportEngine engine = new ReportExportEngine();
        DemoReportJob job = new DemoReportJob(0);
        Path filePath = tempDir.resolve("empty-report.xlsx");

        ReportExportResult result = engine.write(job, ReportExportCommand.<String>builder()
                .taskId("task-1")
                .params("demo")
                .snapshotMaxId(null)
                .pageSize(2)
                .sheetRowLimit(10)
                .filePath(filePath)
                .build());

        assertEquals(0L, result.getTotal());
        assertEquals(0L, result.getExported());
        assertEquals(1, result.getSheetCount());
        assertTrue(Files.size(filePath) > 0L);
    }

    @Test
    void writeRejectsSheetRowsOverLimit() {
        ReportExportEngine engine = new ReportExportEngine();
        DemoReportJob job = new DemoReportJob(3);
        Path filePath = tempDir.resolve("too-large-report.xlsx");

        assertThrows(IllegalStateException.class, () -> engine.write(job, ReportExportCommand.<String>builder()
                .taskId("task-1")
                .params("demo")
                .snapshotMaxId(3L)
                .pageSize(2)
                .sheetRowLimit(2)
                .filePath(filePath)
                .build()));
    }

    private static class DemoReportJob implements ReportExportJob<String> {

        private final int total;

        private int queryCount;

        private DemoReportJob(int total) {
            this.total = total;
        }

        @Override
        public String buildFileName(String businessKey, String params) {
            return businessKey + ".xlsx";
        }

        @Override
        public Long resolveSnapshotMaxId(String params) {
            return total == 0 ? null : (long) total;
        }

        @Override
        public List<ReportSheetConfig> getSheetConfigs(String params) {
            return Collections.singletonList(ReportSheetConfig.builder()
                    .sheetIndex(0)
                    .sheetName("demo")
                    .headClass(DemoRow.class)
                    .build());
        }

        @Override
        public long count(String params, ReportSheetConfig sheetConfig, Long snapshotMaxId) {
            return total;
        }

        @Override
        public ReportPage queryPage(String params, ReportSheetConfig sheetConfig, ReportPageCursor cursor) {
            queryCount++;
            int start = (int) cursor.getLastCursor();
            int end = Math.min(total, start + cursor.getPageSize());
            if (start >= end) {
                return ReportPage.builder()
                        .rows(Collections.emptyList())
                        .nextCursor(cursor.getLastCursor())
                        .build();
            }
            List<DemoRow> rows = new ArrayList<DemoRow>(end - start);
            for (int index = start; index < end; index++) {
                rows.add(new DemoRow("row-" + index));
            }
            return ReportPage.builder()
                    .rows(rows)
                    .nextCursor(end)
                    .build();
        }

        private int getQueryCount() {
            return queryCount;
        }
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DemoRow {

        @ExcelProperty("名称")
        private String name;
    }
}
