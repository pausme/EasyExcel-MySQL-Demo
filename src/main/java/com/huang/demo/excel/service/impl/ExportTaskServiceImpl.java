package com.huang.demo.excel.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.config.MinioProperties;
import com.huang.demo.excel.domain.model.ExportTask;
import com.huang.demo.excel.domain.model.ExportTaskStatus;
import com.huang.demo.excel.domain.model.StudentExportRecord;
import com.huang.demo.excel.model.StudentExcelRow;
import com.huang.demo.excel.repository.StudentMapper;
import com.huang.demo.excel.service.ExportTaskService;
import com.huang.demo.excel.service.MinioObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private static final int MIN_EXPORT_PAGE_SIZE = 1000;
    private static final int MAX_EXPORT_PAGE_SIZE = 10000;
    private static final int MAX_SHEET_DATA_ROWS = 1048575;
    private static final String EXPORT_TASK_KEY_PREFIX = "excel:student:export:";

    private final StudentMapper studentMapper;
    private final ExcelDemoProperties properties;
    private final Executor exportTaskExecutor;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final MinioObjectStorageService minioObjectStorageService;
    private final MinioProperties minioProperties;

    public ExportTaskServiceImpl(StudentMapper studentMapper,
                                 ExcelDemoProperties properties,
                                 @Qualifier("exportTaskExecutor") Executor exportTaskExecutor,
                                 StringRedisTemplate stringRedisTemplate,
                                 ObjectMapper objectMapper,
                                 MinioObjectStorageService minioObjectStorageService,
                                 MinioProperties minioProperties) {
        this.studentMapper = studentMapper;
        this.properties = properties;
        this.exportTaskExecutor = exportTaskExecutor;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.minioObjectStorageService = minioObjectStorageService;
        this.minioProperties = minioProperties;
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

        ExportTask task = ExportTask.builder()
                .taskId(taskId)
                .status(ExportTaskStatus.QUEUED)
                .snapshotMaxId(maxId)
                .fileName(fileName)
                .createdAt(LocalDateTime.now())
                .build();
        saveTaskRequired(task);

        try {
            exportTaskExecutor.execute(() -> executeExport(task));
        } catch (RuntimeException ex) {
            task.setStatus(ExportTaskStatus.FAILED);
            task.setErrorMessage("导出任务提交失败");
            task.setFinishedAt(LocalDateTime.now());
            saveTaskQuietly(task);
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
    public Optional<String> createDownloadUrl(ExportTask task) {
        if (task.getStatus() != ExportTaskStatus.SUCCESS || task.getObjectKey() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(minioObjectStorageService.createDownloadUrl(task.getObjectKey(), task.getFileName()));
        } catch (RuntimeException ex) {
            log.warn("create minio download url failed, taskId={}, objectKey={}", task.getTaskId(), task.getObjectKey(), ex);
            return Optional.empty();
        }
    }

    @Override
    public Optional<InputStream> openDownloadStream(ExportTask task) {
        if (task.getStatus() != ExportTaskStatus.SUCCESS || task.getObjectKey() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(minioObjectStorageService.downloadExcel(task.getObjectKey()));
        } catch (RuntimeException ex) {
            log.warn("download export file from MinIO failed, taskId={}, objectKey={}",
                    task.getTaskId(), task.getObjectKey(), ex);
            return Optional.empty();
        }
    }

    @Scheduled(fixedDelay = 3600000L, initialDelay = 3600000L)
    public void cleanupExpiredExportFiles() {
        cleanupExpiredFiles();
    }

    private void executeExport(ExportTask task) {
        long start = System.currentTimeMillis();
        task.setStatus(ExportTaskStatus.RUNNING);
        saveTaskQuietly(task);
        try {
            Path temporaryFilePath = getTemporaryFilePath(task);
            Long maxId = task.getSnapshotMaxId();
            task.setTotal(maxId == null ? 0 : studentMapper.countByMaxId(maxId));
            if (task.getTotal() > getSheetRowLimit()) {
                throw new IllegalStateException("导出数据超过 Excel 单 Sheet 最大行数，请缩小导出范围或改用 CSV");
            }
            saveTaskQuietly(task);
            Files.createDirectories(temporaryFilePath.getParent());
            Files.deleteIfExists(temporaryFilePath);
            writeExcel(task, temporaryFilePath);
            storeExportFile(task, temporaryFilePath);
            task.setStatus(ExportTaskStatus.SUCCESS);
            task.setFinishedAt(LocalDateTime.now());
            saveTaskQuietly(task);
            log.info("export task finished, taskId={}, total={}, exported={}, sheetCount={}, elapsedMs={}",
                    task.getTaskId(), task.getTotal(), task.getExported(), task.getSheetCount(),
                    System.currentTimeMillis() - start);
        } catch (Exception ex) {
            task.setStatus(ExportTaskStatus.FAILED);
            task.setErrorMessage(ex.getMessage() == null ? "导出失败，请查看服务端日志" : ex.getMessage());
            task.setFinishedAt(LocalDateTime.now());
            deletePartialFile(task);
            saveTaskQuietly(task);
            log.error("export task failed, taskId={}, elapsedMs={}",
                    task.getTaskId(), System.currentTimeMillis() - start, ex);
        } finally {
            deleteTemporaryFileQuietly(task);
        }
    }

    private void storeExportFile(ExportTask task, Path temporaryFilePath) {
        String objectKey = buildExportObjectKey(task);
        minioObjectStorageService.uploadExcel(temporaryFilePath, objectKey);
        task.setObjectKey(objectKey);
    }

    private void writeExcel(ExportTask task, Path filePath) {
        int pageSize = getExportPageSize();
        Long maxId = task.getSnapshotMaxId();
        long lastId = 0L;
        WriteSheet writeSheet = EasyExcel.writerSheet(0, "学生数据").build();

        try (ExcelWriter writer = EasyExcel.write(filePath.toFile(), StudentExcelRow.class).build()) {
            if (maxId != null) {
                while (true) {
                    List<StudentExportRecord> records =
                            studentMapper.listByCursor(lastId, maxId, pageSize);
                    if (records.isEmpty()) {
                        break;
                    }

                    List<StudentExcelRow> rows = toExcelRows(records);
                    writer.write(rows, writeSheet);
                    task.setSheetCount(1);
                    task.setExported(task.getExported() + rows.size());
                    saveTaskQuietly(task);

                    lastId = records.get(records.size() - 1).getId();
                    log.debug("export cursor page finished, taskId={}, lastId={}, pageRows={}, exported={}",
                            task.getTaskId(), lastId, records.size(), task.getExported());
                }
            }

            if (task.getExported() == 0) {
                writer.write(Collections.emptyList(), writeSheet);
                task.setSheetCount(1);
                saveTaskQuietly(task);
            }
        }
    }

    private void saveTaskRequired(ExportTask task) {
        if (!saveTask(task)) {
            throw new IllegalStateException("导出任务状态写入 Redis 失败");
        }
    }

    private void saveTaskQuietly(ExportTask task) {
        saveTask(task);
    }

    private boolean saveTask(ExportTask task) {
        try {
            stringRedisTemplate.opsForValue().set(
                    buildTaskKey(task.getTaskId()),
                    objectMapper.writeValueAsString(task),
                    Duration.ofHours(Math.max(1, properties.getExportFileRetentionHours())));
            return true;
        } catch (JsonProcessingException ex) {
            log.warn("serialize export task failed, taskId={}", task.getTaskId(), ex);
            return false;
        } catch (RuntimeException ex) {
            log.warn("save export task to redis failed, taskId={}", task.getTaskId(), ex);
            return false;
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

    private int getExportPageSize() {
        int pageSize = properties.getExportPageSize();
        if (pageSize < MIN_EXPORT_PAGE_SIZE) {
            return MIN_EXPORT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_EXPORT_PAGE_SIZE);
    }

    private Path getExportDirectory() {
        String configuredPath = properties.getExportTempDir();
        if (configuredPath == null || configuredPath.trim().isEmpty()) {
            configuredPath = System.getProperty("java.io.tmpdir") + "/student-excel-export";
        }
        return Paths.get(configuredPath);
    }

    private Path getTemporaryFilePath(ExportTask task) {
        return getExportDirectory().resolve(task.getFileName() + ".part");
    }

    private String buildExportObjectKey(ExportTask task) {
        String prefix = minioProperties.getExportObjectPrefix();
        if (prefix == null || prefix.trim().isEmpty()) {
            prefix = "excel/student";
        }
        prefix = prefix.replaceAll("^/+", "").replaceAll("/+$", "");
        return prefix + "/" + task.getFileName();
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
                if (!fileName.endsWith(".part")) {
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
            Files.deleteIfExists(getTemporaryFilePath(task));
        } catch (IOException cleanupException) {
            log.warn("delete partial export file failed, taskId={}", task.getTaskId(), cleanupException);
        }
    }

    private void deleteTemporaryFileQuietly(ExportTask task) {
        try {
            Files.deleteIfExists(getTemporaryFilePath(task));
        } catch (IOException ex) {
            log.warn("delete export temporary file failed, taskId={}", task.getTaskId(), ex);
        }
    }
}
