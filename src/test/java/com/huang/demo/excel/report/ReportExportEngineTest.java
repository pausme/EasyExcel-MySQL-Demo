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
import java.util.zip.ZipFile;

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
        // A11 修复：进度封顶与导入统一为 95（成功后置 100）
        assertTrue(progressList.contains(95));
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

    @Test
    void writeCsvExportsRowsWithoutSheetLimit() throws Exception {
        ReportExportEngine engine = new ReportExportEngine();
        DemoReportJob job = new DemoReportJob(3);
        Path filePath = tempDir.resolve("demo-report.csv");

        ReportExportResult result = engine.writeCsv(job, ReportExportCommand.<String>builder()
                .taskId("task-1")
                .params("demo")
                .snapshotMaxId(3L)
                .pageSize(2)
                .sheetRowLimit(1)
                .filePath(filePath)
                .build());

        String csv = new String(Files.readAllBytes(filePath), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(3L, result.getExported());
        assertEquals(1, result.getSheetCount());
        assertTrue(csv.contains("名称"));
        assertTrue(csv.contains("\"row,1\""));
    }

    @Test
    void writeCsvPartsSplitsRowsIntoZipEntries() throws Exception {
        ReportExportEngine engine = new ReportExportEngine();
        DemoReportJob job = new DemoReportJob(5);
        Path filePath = tempDir.resolve("demo-report.zip");

        ReportExportResult result = engine.writeCsvParts(job, ReportExportCommand.<String>builder()
                .taskId("task-1")
                .params("demo")
                .snapshotMaxId(5L)
                .pageSize(2)
                .sheetRowLimit(1)
                .filePath(filePath)
                .build(), 2);

        assertEquals(5L, result.getExported());
        assertEquals(3, result.getSheetCount());
        try (ZipFile zipFile = new ZipFile(filePath.toFile())) {
            assertTrue(zipFile.getEntry("part-001.csv") != null);
            assertTrue(zipFile.getEntry("part-002.csv") != null);
            assertTrue(zipFile.getEntry("part-003.csv") != null);
        }
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
                rows.add(new DemoRow(index == 1 ? "row,1" : "row-" + index));
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

    @Test
    void csvEscapingNeutralizesFormulaInjection() throws Exception {
        ReportExportEngine engine = new ReportExportEngine();
        // 首列为公式注入载体：= + - @ 开头
        DemoFormulaJob job = new DemoFormulaJob(java.util.Arrays.asList(
                java.util.Arrays.asList("=SUM(A1:A2)", "normal"),
                java.util.Arrays.asList("+cmd|' /C calc'", "normal2"),
                java.util.Arrays.asList("-1+1", "normal3"),
                java.util.Arrays.asList("@cmd", "normal4"),
                java.util.Arrays.asList("plain,value", "has,comma")));
        Path csv = tempDir.resolve("formula.csv");
        engine.writeCsv(job, ReportExportCommand.<java.util.List<String>>builder()
                .taskId("t-formula")
                .params(null)
                .snapshotMaxId(5L)
                .pageSize(10)
                .sheetRowLimit(100)
                .filePath(csv)
                .cancelChecker(() -> {})
                .progressUpdater((c, t, p) -> {})
                .build());
        java.util.List<String> lines = java.nio.file.Files.readAllLines(csv);
        org.assertj.core.api.Assertions.assertThat(lines).anySatisfy(line ->
                org.assertj.core.api.Assertions.assertThat(line).startsWith("'=SUM"));
        org.assertj.core.api.Assertions.assertThat(lines).anySatisfy(line ->
                org.assertj.core.api.Assertions.assertThat(line).contains("'+cmd"));
        org.assertj.core.api.Assertions.assertThat(lines).anySatisfy(line ->
                org.assertj.core.api.Assertions.assertThat(line).contains("'-1+1"));
        org.assertj.core.api.Assertions.assertThat(lines).anySatisfy(line ->
                org.assertj.core.api.Assertions.assertThat(line).contains("'@cmd"));
        org.assertj.core.api.Assertions.assertThat(lines).anySatisfy(line ->
                org.assertj.core.api.Assertions.assertThat(line).contains("\"plain,value\""));
    }

    static class DemoFormulaJob implements ReportExportJob<java.util.List<String>> {
        private final java.util.List<java.util.List<String>> rows;
        DemoFormulaJob(java.util.List<java.util.List<String>> rows) { this.rows = rows; }
        @Override public String buildFileName(String businessKey, java.util.List<String> params) { return "f"; }
        @Override public Long resolveSnapshotMaxId(java.util.List<String> params) { return (long) rows.size(); }
        @Override public java.util.List<ReportSheetConfig> getSheetConfigs(java.util.List<String> params) {
            return java.util.Collections.singletonList(ReportSheetConfig.builder()
                    .sheetIndex(0).sheetName("s").headClass(DemoRow.class).build());
        }
        @Override public long count(java.util.List<String> params, ReportSheetConfig cfg, Long maxId) { return rows.size(); }
        @Override public ReportPage queryPage(java.util.List<String> params,
                ReportSheetConfig cfg, ReportPageCursor cursor) {
            if (cursor.getLastCursor() >= rows.size()) {
                return ReportPage.builder().rows(java.util.Collections.emptyList())
                        .nextCursor(cursor.getLastCursor()).build();
            }
            java.util.List<DemoRow> page = new java.util.ArrayList<>();
            for (int i = (int) cursor.getLastCursor(); i < rows.size() && page.size() < cursor.getPageSize(); i++) {
                page.add(new DemoRow(rows.get(i).get(0)));
            }
            return ReportPage.builder()
                    .rows(page).nextCursor((long) rows.size()).build();
        }
    }
}
