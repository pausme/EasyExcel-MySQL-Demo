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
import com.huang.demo.excel.domain.model.StudentExportQuery;
import com.huang.demo.excel.domain.model.StudentExportTaskPayload;
import com.huang.demo.excel.domain.model.StudentExportTaskResult;
import com.huang.demo.excel.model.StudentExcelRow;
import com.huang.demo.excel.repository.StudentMapper;
import com.huang.demo.excel.service.ExportTaskService;
import com.huang.demo.excel.service.MinioObjectStorageService;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.domain.model.AsyncTaskStatus;
import com.huang.demo.task.domain.model.AsyncTaskType;
import com.huang.demo.task.domain.model.CreateAsyncTaskCommand;
import com.huang.demo.task.domain.model.TaskCanceledException;
import com.huang.demo.task.service.TaskCenterService;
import com.huang.demo.task.service.TaskRetryHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class ExportTaskServiceImpl implements ExportTaskService, TaskRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(ExportTaskServiceImpl.class);
    private static final int MIN_EXPORT_PAGE_SIZE = 1000;
    private static final int MAX_EXPORT_PAGE_SIZE = 10000;
    private static final int MAX_SHEET_DATA_ROWS = 1048575;

    private final StudentMapper studentMapper;
    private final ExcelDemoProperties properties;
    private final Executor exportTaskExecutor;
    private final TaskCenterService taskCenterService;
    private final ObjectMapper objectMapper;
    private final MinioObjectStorageService minioObjectStorageService;
    private final MinioProperties minioProperties;

    public ExportTaskServiceImpl(StudentMapper studentMapper,
                                 ExcelDemoProperties properties,
                                 @Qualifier("exportTaskExecutor") Executor exportTaskExecutor,
                                 TaskCenterService taskCenterService,
                                 ObjectMapper objectMapper,
                                 MinioObjectStorageService minioObjectStorageService,
                                 MinioProperties minioProperties) {
        this.studentMapper = studentMapper;
        this.properties = properties;
        this.exportTaskExecutor = exportTaskExecutor;
        this.taskCenterService = taskCenterService;
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
    public ExportTask submitExport(String ownerId) {
        return submitExport(ownerId, UUID.randomUUID().toString().replace("-", ""),
                "学生数据导出", new StudentExportQuery());
    }

    @Override
    public ExportTask submitExport(String ownerId, String businessKey, String taskName, StudentExportQuery query) {
        String normalizedBusinessKey = normalizeBusinessKey(businessKey);
        StudentExportQuery normalizedQuery = normalizeQuery(query);
        String normalizedTaskName = normalizeTaskName(taskName);
        Long maxId = studentMapper.maxIdByQuery(normalizedQuery);
        String fileName = "student-demo-" + normalizedBusinessKey + ".xlsx";
        StudentExportTaskPayload payload = StudentExportTaskPayload.builder()
                .snapshotMaxId(maxId)
                .fileName(fileName)
                .query(normalizedQuery)
                .build();

        AsyncTaskRecord task = taskCenterService.createTask(CreateAsyncTaskCommand.builder()
                .ownerId(ownerId)
                .taskType(AsyncTaskType.EXPORT)
                .taskName(normalizedTaskName)
                .businessKey(normalizedBusinessKey)
                .requestPayload(toJson(payload))
                .build());
        try {
            submitExecution(task.getTaskId());
        } catch (RuntimeException ex) {
            task = taskCenterService.markFailed(task.getTaskId(), "导出任务提交失败");
            log.error("submit export task failed, taskId={}", task.getTaskId(), ex);
        }
        return toExportTask(task);
    }

    @Override
    public Optional<ExportTask> findTask(String taskId) {
        return taskCenterService.findTask(taskId)
                .filter(task -> AsyncTaskType.EXPORT.name().equals(task.getTaskType()))
                .map(this::toExportTask);
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
    public String taskType() {
        return AsyncTaskType.EXPORT.name();
    }

    @Override
    public AsyncTaskRecord retry(AsyncTaskRecord task, String ownerId) {
        AsyncTaskRecord retriedTask = taskCenterService.prepareRetry(task.getTaskId(), ownerId);
        try {
            submitExecution(retriedTask.getTaskId());
        } catch (RuntimeException ex) {
            retriedTask = taskCenterService.markFailed(retriedTask.getTaskId(), "导出任务提交失败");
            log.error("retry export task submit failed, taskId={}", retriedTask.getTaskId(), ex);
        }
        return retriedTask;
    }

    @Scheduled(fixedDelay = 3600000L, initialDelay = 3600000L)
    public void cleanupExpiredExportFiles() {
        cleanupExpiredFiles();
    }

    private void submitExecution(String taskId) {
        exportTaskExecutor.execute(() -> executeExport(taskId));
    }

    private void executeExport(String taskId) {
        long start = System.currentTimeMillis();
        AsyncTaskRecord taskRecord = taskCenterService.markRunning(taskId);
        if (AsyncTaskStatus.CANCELED.name().equals(taskRecord.getStatus())
                || AsyncTaskStatus.EXPIRED.name().equals(taskRecord.getStatus())) {
            return;
        }
        ExportTask task = toExportTask(taskRecord);
        try {
            Path temporaryFilePath = getTemporaryFilePath(task);
            Long maxId = task.getSnapshotMaxId();
            task.setTotal(maxId == null ? 0 : studentMapper.countByMaxIdAndQuery(maxId, task.getQuery()));
            if (task.getTotal() > getSheetRowLimit()) {
                throw new IllegalStateException("导出数据超过 Excel 单 Sheet 最大行数，请缩小导出范围或改用 CSV");
            }
            taskCenterService.updateProgress(task.getTaskId(), 0L, task.getTotal(), 0);
            Files.createDirectories(temporaryFilePath.getParent());
            Files.deleteIfExists(temporaryFilePath);
            writeExcel(task, temporaryFilePath);
            assertTaskCanContinue(task.getTaskId());
            storeExportFile(task, temporaryFilePath);
            AsyncTaskRecord completedTask = taskCenterService.markSuccess(task.getTaskId(), toJson(StudentExportTaskResult.builder()
                    .fileName(task.getFileName())
                    .objectKey(task.getObjectKey())
                    .sheetCount(task.getSheetCount())
                    .build()));
            if (!AsyncTaskStatus.SUCCESS.name().equals(completedTask.getStatus())) {
                minioObjectStorageService.deleteQuietly(task.getObjectKey());
                throw new TaskCanceledException("任务状态已变更，status=" + completedTask.getStatus());
            }
            log.info("export task finished, taskId={}, total={}, exported={}, sheetCount={}, elapsedMs={}",
                    task.getTaskId(), task.getTotal(), task.getExported(), task.getSheetCount(),
                    System.currentTimeMillis() - start);
        } catch (TaskCanceledException ex) {
            deletePartialFile(task);
            log.info("export task canceled, taskId={}, elapsedMs={}",
                    task.getTaskId(), System.currentTimeMillis() - start);
        } catch (Exception ex) {
            deletePartialFile(task);
            taskCenterService.markFailed(task.getTaskId(),
                    ex.getMessage() == null ? "导出失败，请查看服务端日志" : ex.getMessage());
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
                    assertTaskCanContinue(task.getTaskId());
                    List<StudentExportRecord> records =
                            studentMapper.listByCursorAndQuery(lastId, maxId, pageSize, task.getQuery());
                    if (records.isEmpty()) {
                        break;
                    }

                    List<StudentExcelRow> rows = toExcelRows(records);
                    writer.write(rows, writeSheet);
                    task.setSheetCount(1);
                    task.setExported(task.getExported() + rows.size());
                    taskCenterService.updateProgress(
                            task.getTaskId(), task.getExported(), task.getTotal(), calculateProgressPercent(task));

                    lastId = records.get(records.size() - 1).getId();
                    log.debug("export cursor page finished, taskId={}, lastId={}, pageRows={}, exported={}",
                            task.getTaskId(), lastId, records.size(), task.getExported());
                }
            }

            if (task.getExported() == 0) {
                assertTaskCanContinue(task.getTaskId());
                writer.write(Collections.emptyList(), writeSheet);
                task.setSheetCount(1);
                taskCenterService.updateProgress(task.getTaskId(), 0L, 0L, 0);
            }
        }
    }

    private ExportTask toExportTask(AsyncTaskRecord taskRecord) {
        StudentExportTaskPayload payload = readPayload(taskRecord.getRequestPayload());
        StudentExportTaskResult result = readResult(taskRecord.getResultPayload());
        return ExportTask.builder()
                .taskId(taskRecord.getTaskId())
                .ownerId(taskRecord.getOwnerId())
                .status(toExportTaskStatus(taskRecord.getStatus()))
                .progressPercent(safeInt(taskRecord.getProgressPercent()))
                .snapshotMaxId(payload.getSnapshotMaxId())
                .query(normalizeQuery(payload.getQuery()))
                .total(safeLongToInt(taskRecord.getTotalCount()))
                .exported(safeLongToInt(taskRecord.getCompletedCount()))
                .sheetCount(result.getSheetCount())
                .retryCount(safeInt(taskRecord.getRetryCount()))
                .maxRetryCount(safeInt(taskRecord.getMaxRetryCount()))
                .fileName(payload.getFileName() == null ? result.getFileName() : payload.getFileName())
                .objectKey(result.getObjectKey())
                .errorMessage(taskRecord.getErrorMessage())
                .createdAt(taskRecord.getCreatedAt())
                .finishedAt(taskRecord.getFinishedAt())
                .build();
    }

    private ExportTaskStatus toExportTaskStatus(String status) {
        if (AsyncTaskStatus.CREATED.name().equals(status)) {
            return ExportTaskStatus.QUEUED;
        }
        if (AsyncTaskStatus.CANCELED.name().equals(status)) {
            return ExportTaskStatus.CANCELED;
        }
        if (AsyncTaskStatus.EXPIRED.name().equals(status)) {
            return ExportTaskStatus.EXPIRED;
        }
        if (AsyncTaskStatus.RUNNING.name().equals(status)) {
            return ExportTaskStatus.RUNNING;
        }
        if (AsyncTaskStatus.SUCCESS.name().equals(status)) {
            return ExportTaskStatus.SUCCESS;
        }
        return ExportTaskStatus.FAILED;
    }

    private StudentExportTaskPayload readPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.trim().isEmpty()) {
            return new StudentExportTaskPayload();
        }
        try {
            return objectMapper.readValue(payloadJson, StudentExportTaskPayload.class);
        } catch (IOException ex) {
            log.warn("parse student export task payload failed", ex);
            return new StudentExportTaskPayload();
        }
    }

    private StudentExportTaskResult readResult(String resultJson) {
        if (resultJson == null || resultJson.trim().isEmpty()) {
            return new StudentExportTaskResult();
        }
        try {
            return objectMapper.readValue(resultJson, StudentExportTaskResult.class);
        } catch (IOException ex) {
            log.warn("parse student export task result failed", ex);
            return new StudentExportTaskResult();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化导出任务上下文失败", ex);
        }
    }

    private void assertTaskCanContinue(String taskId) {
        AsyncTaskRecord latestTask = taskCenterService.findTask(taskId)
                .orElseThrow(() -> new TaskCanceledException("任务不存在"));
        if (AsyncTaskStatus.CANCELED.name().equals(latestTask.getStatus())) {
            throw new TaskCanceledException("任务已取消");
        }
        if (AsyncTaskStatus.EXPIRED.name().equals(latestTask.getStatus())) {
            throw new TaskCanceledException("任务已过期");
        }
    }

    private int calculateProgressPercent(ExportTask task) {
        if (task.getTotal() <= 0) {
            return 0;
        }
        long progress = task.getExported() * 100L / task.getTotal();
        return (int) Math.min(99L, Math.max(0L, progress));
    }

    private int safeLongToInt(Long value) {
        if (value == null || value <= 0L) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : value.intValue();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
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

    private StudentExportQuery normalizeQuery(StudentExportQuery query) {
        StudentExportQuery safeQuery = query == null ? new StudentExportQuery() : query;
        Integer minAge = safeQuery.getMinAge();
        Integer maxAge = safeQuery.getMaxAge();
        if (minAge != null && maxAge != null && minAge > maxAge) {
            throw new IllegalArgumentException("最小年龄不能大于最大年龄");
        }
        return StudentExportQuery.builder()
                .studentNo(normalizeOptionalText(safeQuery.getStudentNo(), 32))
                .nameKeyword(normalizeOptionalText(safeQuery.getNameKeyword(), 64))
                .className(normalizeOptionalText(safeQuery.getClassName(), 64))
                .gender(normalizeOptionalText(safeQuery.getGender(), 16))
                .minAge(minAge)
                .maxAge(maxAge)
                .build();
    }

    private String normalizeBusinessKey(String businessKey) {
        if (businessKey == null || businessKey.trim().isEmpty()) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        String normalized = businessKey.trim();
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private String normalizeTaskName(String taskName) {
        if (taskName == null || taskName.trim().isEmpty()) {
            return "学生数据导出";
        }
        String normalized = taskName.trim();
        return normalized.length() > 128 ? normalized.substring(0, 128) : normalized;
    }

    private String normalizeOptionalText(String value, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
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
