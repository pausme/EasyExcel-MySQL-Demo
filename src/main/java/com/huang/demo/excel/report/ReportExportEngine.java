package com.huang.demo.excel.report;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportExportEngine {

    public <P> ReportExportResult write(ReportExportJob<P> job, ReportExportCommand<P> command) {
        List<ReportSheetConfig> sheetConfigs = job.getSheetConfigs(command.getParams());
        if (sheetConfigs == null || sheetConfigs.isEmpty()) {
            throw new IllegalStateException("报表 Sheet 配置不能为空");
        }

        Map<Integer, Long> sheetTotals = countSheetRows(job, command, sheetConfigs);
        long total = sumSheetTotals(sheetTotals);
        updateProgress(command, 0L, total, 0);

        long exported = 0L;
        int sheetCount = 0;
        try (ExcelWriter writer = EasyExcel.write(command.getFilePath().toFile()).build()) {
            for (ReportSheetConfig sheetConfig : sheetConfigs) {
                checkCanceled(command);
                WriteSheet writeSheet = EasyExcel.writerSheet(sheetConfig.getSheetIndex(), sheetConfig.getSheetName())
                        .head(sheetConfig.getHeadClass())
                        .build();
                long sheetTotal = sheetTotals.get(sheetConfig.getSheetIndex());
                long lastCursor = 0L;
                long sheetExported = 0L;

                while (command.getSnapshotMaxId() != null && sheetExported < sheetTotal) {
                    checkCanceled(command);
                    ReportPage page = job.queryPage(command.getParams(), sheetConfig, ReportPageCursor.builder()
                            .lastCursor(lastCursor)
                            .maxCursor(command.getSnapshotMaxId())
                            .pageSize(command.getPageSize())
                            .build());
                    if (page == null || page.getRows() == null || page.getRows().isEmpty()) {
                        break;
                    }
                    writer.write(page.getRows(), writeSheet);
                    exported += page.getRows().size();
                    sheetExported += page.getRows().size();
                    if (page.getNextCursor() <= lastCursor) {
                        throw new IllegalStateException("报表分页游标未推进，sheetIndex=" + sheetConfig.getSheetIndex());
                    }
                    lastCursor = page.getNextCursor();
                    updateProgress(command, exported, total, calculateProgressPercent(exported, total));
                }

                if (sheetTotal == 0L) {
                    writer.write(Collections.emptyList(), writeSheet);
                }
                sheetCount++;
            }
        }
        return ReportExportResult.builder()
                .total(total)
                .exported(exported)
                .sheetCount(sheetCount)
                .build();
    }

    private <P> Map<Integer, Long> countSheetRows(ReportExportJob<P> job,
                                                  ReportExportCommand<P> command,
                                                  List<ReportSheetConfig> sheetConfigs) {
        Map<Integer, Long> result = new LinkedHashMap<Integer, Long>();
        for (ReportSheetConfig sheetConfig : sheetConfigs) {
            long total = command.getSnapshotMaxId() == null
                    ? 0L
                    : job.count(command.getParams(), sheetConfig, command.getSnapshotMaxId());
            if (total > command.getSheetRowLimit()) {
                throw new IllegalStateException("导出数据超过 Excel 单 Sheet 最大行数，请缩小导出范围或改用 CSV");
            }
            result.put(sheetConfig.getSheetIndex(), total);
        }
        return result;
    }

    private long sumSheetTotals(Map<Integer, Long> sheetTotals) {
        long total = 0L;
        for (Long sheetTotal : sheetTotals.values()) {
            total += sheetTotal == null ? 0L : sheetTotal;
        }
        return total;
    }

    private void checkCanceled(ReportExportCommand<?> command) {
        if (command.getCancelChecker() != null) {
            command.getCancelChecker().checkCanceled();
        }
    }

    private void updateProgress(ReportExportCommand<?> command,
                                long completed,
                                long total,
                                int progressPercent) {
        if (command.getProgressUpdater() != null) {
            command.getProgressUpdater().update(completed, total, progressPercent);
        }
    }

    private int calculateProgressPercent(long exported, long total) {
        if (total <= 0L) {
            return 0;
        }
        long progress = exported * 100L / total;
        return (int) Math.min(99L, Math.max(0L, progress));
    }
}
