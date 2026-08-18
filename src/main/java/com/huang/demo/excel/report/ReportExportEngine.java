package com.huang.demo.excel.report;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ReportExportEngine {

    public <P> ReportExportResult write(ReportExportJob<P> job, ReportExportCommand<P> command) {
        List<ReportSheetConfig> sheetConfigs = job.getSheetConfigs(command.getParams());
        if (sheetConfigs == null || sheetConfigs.isEmpty()) {
            throw new IllegalStateException("报表 Sheet 配置不能为空");
        }

        Map<Integer, Long> sheetTotals = countSheetRows(job, command, sheetConfigs, true);
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

    public <P> ReportExportResult writeCsv(ReportExportJob<P> job, ReportExportCommand<P> command) {
        List<ReportSheetConfig> sheetConfigs = job.getSheetConfigs(command.getParams());
        if (sheetConfigs == null || sheetConfigs.isEmpty()) {
            throw new IllegalStateException("报表 Sheet 配置不能为空");
        }
        if (sheetConfigs.size() != 1) {
            throw new IllegalStateException("CSV 导出仅支持单 Sheet 报表，多 Sheet 请使用 ZIP_CSV_PARTS");
        }

        Map<Integer, Long> sheetTotals = countSheetRows(job, command, sheetConfigs, false);
        long total = sumSheetTotals(sheetTotals);
        updateProgress(command, 0L, total, 0);

        ReportSheetConfig sheetConfig = sheetConfigs.get(0);
        long exported = 0L;
        long lastCursor = 0L;
        long sheetTotal = sheetTotals.get(sheetConfig.getSheetIndex());
        List<Field> fields = resolveCsvFields(sheetConfig.getHeadClass());

        try (BufferedWriter writer = Files.newBufferedWriter(command.getFilePath(), StandardCharsets.UTF_8)) {
            writer.write('\ufeff');
            writeCsvLine(writer, resolveCsvHeaders(fields));
            while (command.getSnapshotMaxId() != null && exported < sheetTotal) {
                checkCanceled(command);
                ReportPage page = job.queryPage(command.getParams(), sheetConfig, ReportPageCursor.builder()
                        .lastCursor(lastCursor)
                        .maxCursor(command.getSnapshotMaxId())
                        .pageSize(command.getPageSize())
                        .build());
                if (page == null || page.getRows() == null || page.getRows().isEmpty()) {
                    break;
                }
                for (Object row : page.getRows()) {
                    writeCsvLine(writer, resolveCsvValues(fields, row));
                }
                exported += page.getRows().size();
                if (page.getNextCursor() <= lastCursor) {
                    throw new IllegalStateException("报表分页游标未推进，sheetIndex=" + sheetConfig.getSheetIndex());
                }
                lastCursor = page.getNextCursor();
                updateProgress(command, exported, total, calculateProgressPercent(exported, total));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("写入 CSV 文件失败", ex);
        }

        return ReportExportResult.builder()
                .total(total)
                .exported(exported)
                .sheetCount(1)
                .build();
    }

    public <P> ReportExportResult writeCsvParts(ReportExportJob<P> job,
                                                ReportExportCommand<P> command,
                                                int partRowLimit) {
        List<ReportSheetConfig> sheetConfigs = job.getSheetConfigs(command.getParams());
        if (sheetConfigs == null || sheetConfigs.isEmpty()) {
            throw new IllegalStateException("报表 Sheet 配置不能为空");
        }
        if (sheetConfigs.size() != 1) {
            throw new IllegalStateException("分片 CSV 导出仅支持单 Sheet 报表");
        }

        Map<Integer, Long> sheetTotals = countSheetRows(job, command, sheetConfigs, false);
        long total = sumSheetTotals(sheetTotals);
        updateProgress(command, 0L, total, 0);

        ReportSheetConfig sheetConfig = sheetConfigs.get(0);
        long exported = 0L;
        long lastCursor = 0L;
        long sheetTotal = sheetTotals.get(sheetConfig.getSheetIndex());
        int safePartRowLimit = Math.max(1, partRowLimit);
        int partIndex = 1;
        int currentPartRows = 0;
        List<Field> fields = resolveCsvFields(sheetConfig.getHeadClass());
        BufferedWriter writer = null;

        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(command.getFilePath()))) {
            writer = openCsvPart(zipOutputStream, partIndex, fields);
            while (command.getSnapshotMaxId() != null && exported < sheetTotal) {
                checkCanceled(command);
                ReportPage page = job.queryPage(command.getParams(), sheetConfig, ReportPageCursor.builder()
                        .lastCursor(lastCursor)
                        .maxCursor(command.getSnapshotMaxId())
                        .pageSize(command.getPageSize())
                        .build());
                if (page == null || page.getRows() == null || page.getRows().isEmpty()) {
                    break;
                }
                for (Object row : page.getRows()) {
                    if (currentPartRows >= safePartRowLimit) {
                        writer.flush();
                        zipOutputStream.closeEntry();
                        partIndex++;
                        currentPartRows = 0;
                        writer = openCsvPart(zipOutputStream, partIndex, fields);
                    }
                    writeCsvLine(writer, resolveCsvValues(fields, row));
                    currentPartRows++;
                }
                exported += page.getRows().size();
                if (page.getNextCursor() <= lastCursor) {
                    throw new IllegalStateException("报表分页游标未推进，sheetIndex=" + sheetConfig.getSheetIndex());
                }
                lastCursor = page.getNextCursor();
                updateProgress(command, exported, total, calculateProgressPercent(exported, total));
            }
            writer.flush();
            zipOutputStream.closeEntry();
        } catch (IOException ex) {
            throw new IllegalStateException("写入分片 CSV 压缩包失败", ex);
        }

        return ReportExportResult.builder()
                .total(total)
                .exported(exported)
                .sheetCount(partIndex)
                .build();
    }

    private <P> Map<Integer, Long> countSheetRows(ReportExportJob<P> job,
                                                  ReportExportCommand<P> command,
                                                  List<ReportSheetConfig> sheetConfigs,
                                                  boolean enforceSheetRowLimit) {
        Map<Integer, Long> result = new LinkedHashMap<Integer, Long>();
        for (ReportSheetConfig sheetConfig : sheetConfigs) {
            long total = command.getSnapshotMaxId() == null
                    ? 0L
                    : job.count(command.getParams(), sheetConfig, command.getSnapshotMaxId());
            if (enforceSheetRowLimit && total > command.getSheetRowLimit()) {
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

    private List<Field> resolveCsvFields(Class<?> headClass) {
        if (headClass == null) {
            throw new IllegalStateException("CSV 表头类型不能为空");
        }
        Field[] declaredFields = headClass.getDeclaredFields();
        List<Field> result = new java.util.ArrayList<Field>(declaredFields.length);
        for (Field field : declaredFields) {
            if (field.isSynthetic()) {
                continue;
            }
            field.setAccessible(true);
            result.add(field);
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("CSV 表头字段不能为空");
        }
        return result;
    }

    private List<String> resolveCsvHeaders(List<Field> fields) {
        List<String> result = new java.util.ArrayList<String>(fields.size());
        for (Field field : fields) {
            ExcelProperty excelProperty = field.getAnnotation(ExcelProperty.class);
            if (excelProperty != null && excelProperty.value().length > 0) {
                result.add(excelProperty.value()[0]);
            } else {
                result.add(field.getName());
            }
        }
        return result;
    }

    private List<String> resolveCsvValues(List<Field> fields, Object row) {
        List<String> result = new java.util.ArrayList<String>(fields.size());
        for (Field field : fields) {
            try {
                Object value = field.get(row);
                result.add(value == null ? "" : String.valueOf(value));
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException("读取 CSV 字段失败，field=" + field.getName(), ex);
            }
        }
        return result;
    }

    private void writeCsvLine(BufferedWriter writer, List<String> values) throws IOException {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                writer.write(',');
            }
            writer.write(escapeCsvValue(values.get(index)));
        }
        writer.newLine();
    }

    private BufferedWriter openCsvPart(ZipOutputStream zipOutputStream,
                                       int partIndex,
                                       List<Field> fields) throws IOException {
        zipOutputStream.putNextEntry(new ZipEntry(String.format("part-%03d.csv", partIndex)));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(zipOutputStream, StandardCharsets.UTF_8));
        writer.write('\ufeff');
        writeCsvLine(writer, resolveCsvHeaders(fields));
        return writer;
    }

    private String escapeCsvValue(String value) {
        String safeValue = value == null ? "" : value;
        if (safeValue.indexOf(',') < 0
                && safeValue.indexOf('"') < 0
                && safeValue.indexOf('\n') < 0
                && safeValue.indexOf('\r') < 0) {
            return safeValue;
        }
        return "\"" + safeValue.replace("\"", "\"\"") + "\"";
    }

    private int calculateProgressPercent(long exported, long total) {
        if (total <= 0L) {
            return 0;
        }
        long progress = exported * 100L / total;
        return (int) Math.min(99L, Math.max(0L, progress));
    }
}
