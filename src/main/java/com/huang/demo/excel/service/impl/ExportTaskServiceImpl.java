package com.huang.demo.excel.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.domain.model.ExportTask;
import com.huang.demo.excel.domain.model.ExportTaskStatus;
import com.huang.demo.excel.domain.model.StudentExportRecord;
import com.huang.demo.excel.model.StudentExcelRow;
import com.huang.demo.excel.repository.StudentMapper;
import com.huang.demo.excel.service.ExportTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class ExportTaskServiceImpl implements ExportTaskService {

    private static final Logger log = LoggerFactory.getLogger(ExportTaskServiceImpl.class);
    private static final int MAX_SHEET_DATA_ROWS = 50000;
    private static final String EXPORT_TASK_KEY_PREFIX = "excel:student:export:";

    private final StudentMapper studentMapper;
    private final ExcelDemoProperties properties;
    private final Executor exportTaskExecutor;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public ExportTaskServiceImpl(StudentMapper studentMapper,
                                 ExcelDemoProperties properties,
                                 @Qualifier("exportTaskExecutor") Executor exportTaskExecutor,
                                 StringRedisTemplate stringRedisTemplate,
                                 ObjectMapper objectMapper) {
        this.studentMapper = studentMapper;
        this.properties = properties;
        this.exportTaskExecutor = exportTaskExecutor;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initializeExportDirectory() {
        try {
            Files.createDirectories(getExportDirectory());
            cleanupExpiredFiles();
        } catch (IOException ex) {
            log.warn("initialize export directory failed, path={}", getExportDirectory(), ex);
        }
    }

    @Override
    public ExportTask submitExport() {
        String taskId = UUID.randomUUID().toString().replace("-", "");
        Long maxId = studentMapper.maxId();
        String fileName = "student-demo-" + taskId + ".xlsx";
        Path filePath = getExportDirectory().resolve(fileName);

        ExportTask task = ExportTask.builder()
                .taskId(taskId)
                .status(ExportTaskStatus.QUEUED)
                .snapshotMaxId(maxId)
                .fileName(fileName)
                .filePath(filePath.toString())
                .createdAt(LocalDateTime.now())
                .build();
        saveTask(task);

        try {
            exportTaskExecutor.execute(() -> executeExport(task));
        } catch (RuntimeException ex) {
            task.setStatus(ExportTaskStatus.FAILED);
            task.setErrorMessage("导出任务提交失败");
            task.setFinishedAt(LocalDateTime.now());
            saveTask(task);
            log.error("submit export task failed, taskId={}", taskId, ex);
        }
        return task;
    }

    @Override
    public Optional<ExportTask> findTask(String taskId) {
        String json = stringRedisTemplate.opsForValue().get(buildTaskKey(taskId));
        if (json == null || json.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, ExportTask.class));
        } catch (IOException ex) {
            log.warn("parse export task from redis failed, taskId={}", taskId, ex);
            return Optional.empty();
        }
    }

    @Override
    public Optional<Path> findCompletedFile(String taskId) {
        Optional<ExportTask> optionalTask = findTask(taskId);
        if (!optionalTask.isPresent() || optionalTask.get().getStatus() != ExportTaskStatus.SUCCESS) {
            return Optional.empty();
        }
        Path path = Paths.get(optionalTask.get().getFilePath());
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    @Scheduled(fixedDelay = 3600000L, initialDelay = 3600000L)
    public void cleanupExpiredExportFiles() {
        cleanupExpiredFiles();
    }

    private void executeExport(ExportTask task) {
        long start = System.currentTimeMillis();
        task.setStatus(ExportTaskStatus.RUNNING);
        saveTask(task);
        try {
            Path filePath = Paths.get(task.getFilePath());
            Path temporaryFilePath = filePath.resolveSibling(filePath.getFileName() + ".part");
            Long maxId = task.getSnapshotMaxId();
            task.setTotal(maxId == null ? 0 : studentMapper.countByMaxId(maxId));
            saveTask(task);
            Files.createDirectories(filePath.getParent());
            Files.deleteIfExists(temporaryFilePath);
            writeExcel(task, temporaryFilePath);
            Files.move(temporaryFilePath, filePath, StandardCopyOption.REPLACE_EXISTING);
            task.setStatus(ExportTaskStatus.SUCCESS);
            task.setFinishedAt(LocalDateTime.now());
            saveTask(task);
            log.info("export task finished, taskId={}, total={}, exported={}, sheetCount={}, elapsedMs={}",
                    task.getTaskId(), task.getTotal(), task.getExported(), task.getSheetCount(),
                    System.currentTimeMillis() - start);
        } catch (Exception ex) {
            task.setStatus(ExportTaskStatus.FAILED);
            task.setErrorMessage("导出失败，请查看服务端日志");
            task.setFinishedAt(LocalDateTime.now());
            deletePartialFile(task);
            saveTask(task);
            log.error("export task failed, taskId={}, elapsedMs={}",
                    task.getTaskId(), System.currentTimeMillis() - start, ex);
        }
    }

    private void writeExcel(ExportTask task, Path filePath) {
        int pageSize = Math.max(1, properties.getExportPageSize());
        int sheetRowLimit = getSheetRowLimit();
        Long maxId = task.getSnapshotMaxId();
        long lastId = 0L;
        int sheetRows = 0;
        int sheetIndex = 0;
        WriteSheet writeSheet = null;

        try (ExcelWriter writer = EasyExcel.write(filePath.toFile(), StudentExcelRow.class).build()) {
            if (maxId != null) {
                while (true) {
                    List<StudentExportRecord> records =
                            studentMapper.listByCursor(lastId, maxId, pageSize);
                    if (records.isEmpty()) {
                        break;
                    }

                    int recordIndex = 0;
                    while (recordIndex < records.size()) {
                        if (writeSheet == null || sheetRows >= sheetRowLimit) {
                            sheetIndex++;
                            sheetRows = 0;
                            writeSheet = EasyExcel.writerSheet(sheetIndex - 1, "学生数据-" + sheetIndex).build();
                            task.setSheetCount(sheetIndex);
                            saveTask(task);
                        }

                        int capacity = sheetRowLimit - sheetRows;
                        int endIndex = Math.min(records.size(), recordIndex + capacity);
                        List<StudentExcelRow> rows = toExcelRows(records.subList(recordIndex, endIndex));
                        writer.write(rows, writeSheet);
                        sheetRows += rows.size();
                        task.setExported(task.getExported() + rows.size());
                        saveTask(task);
                        recordIndex = endIndex;
                    }

                    lastId = records.get(records.size() - 1).getId();
                    log.debug("export cursor page finished, taskId={}, lastId={}, pageRows={}, exported={}",
                            task.getTaskId(), lastId, records.size(), task.getExported());
                }
            }

            if (sheetIndex == 0) {
                WriteSheet emptySheet = EasyExcel.writerSheet(0, "学生数据-1").build();
                writer.write(Collections.emptyList(), emptySheet);
                task.setSheetCount(1);
                saveTask(task);
            }
        }
    }

    private void saveTask(ExportTask task) {
        try {
            stringRedisTemplate.opsForValue().set(
                    buildTaskKey(task.getTaskId()),
                    objectMapper.writeValueAsString(task),
                    Duration.ofHours(Math.max(1, properties.getExportFileRetentionHours())));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("导出任务序列化失败", ex);
        }
    }

    private String buildTaskKey(String taskId) {
        return EXPORT_TASK_KEY_PREFIX + taskId;
    }

    private List<StudentExcelRow> toExcelRows(List<StudentExportRecord> records) {
        List<StudentExcelRow> rows = new ArrayList<StudentExcelRow>(records.size());
        for (StudentExportRecord record : records) {
            rows.add(StudentExcelRow.builder()
                    .studentNo(record.getStudentNo())
                    .name(record.getName())
                    .age(record.getAge())
                    .gender(record.getGender())
                    .className(record.getClassName())
                    .email(record.getEmail())
                    .birthday(record.getBirthday())
                    .build());
        }
        return rows;
    }

    private int getSheetRowLimit() {
        return Math.min(MAX_SHEET_DATA_ROWS, Math.max(1, properties.getSheetRowLimit()));
    }

    private Path getExportDirectory() {
        String configuredPath = properties.getExportTempDir();
        if (configuredPath == null || configuredPath.trim().isEmpty()) {
            configuredPath = System.getProperty("java.io.tmpdir") + "/student-excel-export";
        }
        return Paths.get(configuredPath);
    }

    private void cleanupExpiredFiles() {
        Path exportDirectory = getExportDirectory();
        if (!Files.isDirectory(exportDirectory)) {
            return;
        }
        long retentionMillis = Math.max(1, properties.getExportFileRetentionHours())
                * 60L * 60L * 1000L;
        long expireBefore = System.currentTimeMillis() - retentionMillis;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(exportDirectory)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String fileName = path.getFileName().toString();
                if (!fileName.endsWith(".xlsx") && !fileName.endsWith(".part")) {
                    continue;
                }
                if (Files.getLastModifiedTime(path).toMillis() < expireBefore) {
                    Files.deleteIfExists(path);
                    log.info("expired export file deleted, filePath={}", path);
                }
            }
        } catch (IOException ex) {
            log.warn("cleanup expired export files failed, path={}", exportDirectory, ex);
        }
    }

    private void deletePartialFile(ExportTask task) {
        try {
            Path filePath = Paths.get(task.getFilePath());
            Files.deleteIfExists(filePath);
            Files.deleteIfExists(filePath.resolveSibling(filePath.getFileName() + ".part"));
        } catch (IOException cleanupException) {
            log.warn("delete partial export file failed, taskId={}, filePath={}",
                    task.getTaskId(), task.getFilePath(), cleanupException);
        }
    }
}
