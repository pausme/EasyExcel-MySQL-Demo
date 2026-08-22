package com.huang.demo.excel.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.common.compensation.domain.model.CompensationFailureType;
import com.huang.demo.common.compensation.service.CompensationService;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.config.MinioProperties;
import com.huang.demo.excel.domain.model.ExportTask;
import com.huang.demo.excel.domain.model.ExportTaskStatus;
import com.huang.demo.excel.domain.model.StudentExportFormat;
import com.huang.demo.excel.domain.model.StudentExportQuery;
import com.huang.demo.excel.domain.model.StudentExportTaskPayload;
import com.huang.demo.excel.domain.model.StudentExportTaskResult;
import com.huang.demo.excel.report.ReportCancelChecker;
import com.huang.demo.excel.report.ReportExportCommand;
import com.huang.demo.excel.report.ReportExportEngine;
import com.huang.demo.excel.report.ReportExportResult;
import com.huang.demo.excel.report.ReportProgressUpdater;
import com.huang.demo.excel.service.ExportTaskService;
import com.huang.demo.excel.service.MinioObjectStorageService;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.domain.model.AsyncTaskFailureType;
import com.huang.demo.task.domain.model.AsyncTaskStatus;
import com.huang.demo.task.domain.model.AsyncTaskType;
import com.huang.demo.task.domain.model.CreateAsyncTaskCommand;
import com.huang.demo.task.domain.model.MarkAsyncTaskFailedCommand;
import com.huang.demo.task.domain.model.TaskCanceledException;
import com.huang.demo.task.service.TaskCenterService;
import com.huang.demo.task.service.TaskExecutionGuard;
import com.huang.demo.task.service.TaskRecoveryHandler;
import com.huang.demo.task.service.TaskRetryHandler;
import com.huang.demo.task.monitor.TaskMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class ExportTaskServiceImpl implements ExportTaskService, TaskRetryHandler, TaskRecoveryHandler {

    private static final Logger log = LoggerFactory.getLogger(ExportTaskServiceImpl.class);
    private static final int MIN_EXPORT_PAGE_SIZE = 1000;
    private static final int MAX_EXPORT_PAGE_SIZE = 10000;
    private static final int MAX_SHEET_DATA_ROWS = 1048575;

    private final ExcelDemoProperties properties;
    private final Executor exportTaskExecutor;
    private final TaskCenterService taskCenterService;
    private final ObjectMapper objectMapper;
    private final MinioObjectStorageService minioObjectStorageService;
    private final MinioProperties minioProperties;
    private final ReportExportEngine reportExportEngine;
    private final StudentReportExportJob studentReportExportJob;
    private final TaskExecutionGuard taskExecutionGuard;
    private final CompensationService compensationService;
    private final TaskMetricsService taskMetricsService;

    @Autowired
    public ExportTaskServiceImpl(ExcelDemoProperties properties,
                                 @Qualifier("exportTaskExecutor") Executor exportTaskExecutor,
                                 TaskCenterService taskCenterService,
                                 ObjectMapper objectMapper,
                                 MinioObjectStorageService minioObjectStorageService,
                                 MinioProperties minioProperties,
                                 ReportExportEngine reportExportEngine,
                                 StudentReportExportJob studentReportExportJob,
                                 TaskExecutionGuard taskExecutionGuard,
                                 CompensationService compensationService,
                                 TaskMetricsService taskMetricsService) {
        this.properties = properties;
        this.exportTaskExecutor = exportTaskExecutor;
        this.taskCenterService = taskCenterService;
        this.objectMapper = objectMapper;
        this.minioObjectStorageService = minioObjectStorageService;
        this.minioProperties = minioProperties;
        this.reportExportEngine = reportExportEngine;
        this.studentReportExportJob = studentReportExportJob;
        this.taskExecutionGuard = taskExecutionGuard;
        this.compensationService = compensationService;
        this.taskMetricsService = taskMetricsService;
    }

    public ExportTaskServiceImpl(ExcelDemoProperties properties,
                                 Executor exportTaskExecutor,
                                 TaskCenterService taskCenterService,
                                 ObjectMapper objectMapper,
                                 MinioObjectStorageService minioObjectStorageService,
                                 MinioProperties minioProperties,
                                 ReportExportEngine reportExportEngine,
                                 StudentReportExportJob studentReportExportJob) {
        this(properties, exportTaskExecutor, taskCenterService, objectMapper,
                minioObjectStorageService, minioProperties, reportExportEngine,
                studentReportExportJob, new TaskExecutionGuard(taskCenterService), CompensationService.noop(), null);
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
    public ExportTask submitExport(String ownerId, String format) {
        return submitExport(ownerId, UUID.randomUUID().toString().replace("-", ""),
                "学生数据导出", StudentExportQuery.builder()
                        .format(StudentExportFormat.parse(format))
                        .build());
    }

    @Override
    public ExportTask submitExport(String ownerId, String businessKey, String taskName, StudentExportQuery query) {
        String normalizedBusinessKey = normalizeBusinessKey(businessKey);
        StudentExportQuery normalizedQuery = normalizeQuery(query);
        String normalizedTaskName = normalizeTaskName(taskName);
        Long snapshotVersion = studentReportExportJob.resolveSnapshotVersion();
        normalizedQuery.setSnapshotVersion(snapshotVersion);
        Long maxId = studentReportExportJob.resolveSnapshotMaxId(normalizedQuery);
        String fileName = studentReportExportJob.buildFileName(normalizedBusinessKey, normalizedQuery);
        StudentExportTaskPayload payload = StudentExportTaskPayload.builder()
                .snapshotMaxId(maxId)
                .snapshotVersion(snapshotVersion)
                .fileName(fileName)
                .format(normalizedQuery.getFormat())
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
            task = taskCenterService.markFailed(buildRetryableFailure(task.getTaskId(),
                    "导出任务提交失败", AsyncTaskFailureType.SYSTEM_ERROR, "导出任务未进入后台执行队列，可稍后重试"));
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
            minioObjectStorageService.ensureObjectExists(task.getObjectKey());
            return Optional.of(minioObjectStorageService.createDownloadUrl(task.getObjectKey(), task.getFileName()));
        } catch (RuntimeException ex) {
            if (isMissingExportObject(ex)) {
                taskCenterService.markExpired(task.getTaskId(),
                        "导出文件不存在或已过期",
                        "导出文件已被清理，可重试任务或重新提交导出");
            }
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
            retriedTask = taskCenterService.markFailed(buildRetryableFailure(retriedTask.getTaskId(),
                    "导出任务提交失败", AsyncTaskFailureType.SYSTEM_ERROR, "导出任务未进入后台执行队列，可稍后重试"));
            log.error("retry export task submit failed, taskId={}", retriedTask.getTaskId(), ex);
        }
        return retriedTask;
    }

    @Override
    public void recover(AsyncTaskRecord task) {
        submitExecution(task.getTaskId());
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
        Optional<TaskExecutionGuard.TaskExecutionLease> leaseOptional = taskExecutionGuard.tryStart(
                taskId, AsyncTaskType.EXPORT.name(), taskCenterService.currentWorkerId());
        if (!leaseOptional.isPresent()) {
            return;
        }
        try (TaskExecutionGuard.TaskExecutionLease lease = leaseOptional.get()) {
            AsyncTaskRecord taskRecord = lease.getTask();
            ExportTask task = toExportTask(taskRecord);
            try {
                putTaskMdc(taskRecord);
                Path temporaryFilePath = getTemporaryFilePath(task);
                Files.createDirectories(temporaryFilePath.getParent());
                Files.deleteIfExists(temporaryFilePath);
                ReportExportResult exportResult = writeReport(task, temporaryFilePath);
                task.setTotal(safeLongToInt(exportResult.getTotal()));
                task.setExported(safeLongToInt(exportResult.getExported()));
                task.setSheetCount(exportResult.getSheetCount());
                assertTaskCanContinue(task.getTaskId());
                storeExportFile(task, temporaryFilePath);
                AsyncTaskRecord completedTask = taskCenterService.markSuccess(task.getTaskId(), toJson(StudentExportTaskResult.builder()
                        .fileName(task.getFileName())
                        .objectKey(task.getObjectKey())
                        .format(task.getFormat())
                        .sheetCount(task.getSheetCount())
                        .build()));
                if (!AsyncTaskStatus.SUCCESS.name().equals(completedTask.getStatus())) {
                    minioObjectStorageService.deleteQuietly(task.getObjectKey());
                    throw new TaskCanceledException("任务状态已变更，status=" + completedTask.getStatus());
                }
                log.info("export task finished, taskId={}, total={}, exported={}, sheetCount={}, elapsedMs={}",
                        task.getTaskId(), task.getTotal(), task.getExported(), task.getSheetCount(),
                        System.currentTimeMillis() - start);
                recordRowsProcessed("export", task.getExported(), System.currentTimeMillis() - start);
            } catch (TaskCanceledException ex) {
                deletePartialFile(task);
                log.info("export task canceled, taskId={}, elapsedMs={}",
                        task.getTaskId(), System.currentTimeMillis() - start);
            } catch (Exception ex) {
                deletePartialFile(task);
                taskCenterService.markFailed(buildRetryableFailure(task.getTaskId(),
                        ex.getMessage() == null ? "导出失败，请查看服务端日志" : ex.getMessage(),
                        classifyFailure(ex), "可稍后重试；若持续失败，请检查数据库、MinIO 或服务端日志"));
                log.error("export task failed, taskId={}, elapsedMs={}",
                        task.getTaskId(), System.currentTimeMillis() - start, ex);
            } finally {
                clearTaskMdc();
                deleteTemporaryFileQuietly(task);
            }
        }
    }

    private void putTaskMdc(AsyncTaskRecord taskRecord) {
        MDC.put("taskId", taskRecord.getTaskId());
        MDC.put("workerId", taskCenterService.currentWorkerId());
        if (taskRecord.getTraceId() != null && !taskRecord.getTraceId().trim().isEmpty()) {
            MDC.put("traceId", taskRecord.getTraceId());
        }
    }

    private void clearTaskMdc() {
        MDC.remove("taskId");
        MDC.remove("workerId");
        MDC.remove("traceId");
    }

    private void storeExportFile(ExportTask task, Path temporaryFilePath) {
        String objectKey = buildExportObjectKey(task);
        long start = System.currentTimeMillis();
        boolean success = false;
        try {
            minioObjectStorageService.uploadFile(temporaryFilePath, objectKey, task.getFormat().getContentType());
            success = true;
        } catch (RuntimeException ex) {
            compensationService.recordPending(
                    "EXPORT",
                    task.getTaskId(),
                    CompensationFailureType.OBJECT_UPLOAD_FAILED.name(),
                    "objectKey=" + objectKey + ",error=" + safeErrorMessage(ex));
            throw ex;
        } finally {
            recordStorageUpload("export-result", System.currentTimeMillis() - start, success);
        }
        task.setObjectKey(objectKey);
    }

    private void recordRowsProcessed(String scene, long rows, long elapsedMs) {
        if (taskMetricsService != null) {
            taskMetricsService.recordRowsProcessed(scene, rows, elapsedMs);
        }
    }

    private void recordStorageUpload(String scene, long elapsedMs, boolean success) {
        if (taskMetricsService != null) {
            taskMetricsService.recordStorageUpload(scene, elapsedMs, success);
        }
    }

    private String safeErrorMessage(RuntimeException ex) {
        if (ex == null || ex.getMessage() == null) {
            return "unknown";
        }
        String message = ex.getMessage().replace('\n', ' ').replace('\r', ' ');
        return message.length() > 512 ? message.substring(0, 512) : message;
    }

    private MarkAsyncTaskFailedCommand buildRetryableFailure(String taskId,
                                                             String errorMessage,
                                                             AsyncTaskFailureType failureType,
                                                             String suggestion) {
        return MarkAsyncTaskFailedCommand.builder()
                .taskId(taskId)
                .errorMessage(errorMessage)
                .failureType(failureType)
                .retryable(true)
                .failureSuggestion(suggestion)
                .build();
    }

    private AsyncTaskFailureType classifyFailure(Exception ex) {
        String message = ex.getMessage();
        if (message != null && (message.contains("MinIO") || message.contains("文件不存在") || message.contains("已过期"))) {
            return AsyncTaskFailureType.DEPENDENCY_ERROR;
        }
        if (message != null && (message.contains("超过") || message.contains("线程池") || message.contains("超时"))) {
            return AsyncTaskFailureType.RESOURCE_LIMIT;
        }
        return AsyncTaskFailureType.SYSTEM_ERROR;
    }

    private boolean isMissingExportObject(RuntimeException ex) {
        String message = ex.getMessage();
        return message != null && message.contains("MinIO 文件不存在或已过期");
    }

    private ReportExportResult writeReport(ExportTask task, Path filePath) {
        ReportExportCommand<StudentExportQuery> command = ReportExportCommand.<StudentExportQuery>builder()
                .taskId(task.getTaskId())
                .params(task.getQuery())
                .snapshotMaxId(task.getSnapshotMaxId())
                .pageSize(getExportPageSize())
                .sheetRowLimit(getSheetRowLimit())
                .filePath(filePath)
                .cancelChecker(new ReportCancelChecker() {
                    @Override
                    public void checkCanceled() {
                        assertTaskCanContinue(task.getTaskId());
                    }
                })
                .progressUpdater(new ReportProgressUpdater() {
                    @Override
                    public void update(long completedCount, long totalCount, int progressPercent) {
                        taskCenterService.updateProgress(
                        task.getTaskId(), completedCount, totalCount, progressPercent);
                    }
                })
                .build();
        if (task.getFormat() == StudentExportFormat.CSV) {
            return reportExportEngine.writeCsv(studentReportExportJob, command);
        }
        if (task.getFormat() == StudentExportFormat.ZIP_CSV_PARTS) {
            return reportExportEngine.writeCsvParts(studentReportExportJob, command, getSheetRowLimit());
        }
        return reportExportEngine.write(studentReportExportJob, command);
    }

    private ExportTask toExportTask(AsyncTaskRecord taskRecord) {
        StudentExportTaskPayload payload = readPayload(taskRecord.getRequestPayload());
        StudentExportTaskResult result = readResult(taskRecord.getResultPayload());
        StudentExportQuery query = normalizePayloadQuery(payload);
        return ExportTask.builder()
                .taskId(taskRecord.getTaskId())
                .ownerId(taskRecord.getOwnerId())
                .status(toExportTaskStatus(taskRecord.getStatus()))
                .progressPercent(safeInt(taskRecord.getProgressPercent()))
                .snapshotMaxId(payload.getSnapshotMaxId())
                .snapshotVersion(payload.getSnapshotVersion())
                .query(query)
                .total(safeLongToInt(taskRecord.getTotalCount()))
                .exported(safeLongToInt(taskRecord.getCompletedCount()))
                .sheetCount(result.getSheetCount())
                .retryCount(safeInt(taskRecord.getRetryCount()))
                .maxRetryCount(safeInt(taskRecord.getMaxRetryCount()))
                .fileName(payload.getFileName() == null ? result.getFileName() : payload.getFileName())
                .format(resolveExportFormat(payload, result, query))
                .objectKey(result.getObjectKey())
                .errorMessage(taskRecord.getErrorMessage())
                .failureType(taskRecord.getFailureType())
                .retryable(taskRecord.getRetryable())
                .failureSuggestion(taskRecord.getFailureSuggestion())
                .canRetry(canRetry(taskRecord))
                .createdAt(taskRecord.getCreatedAt())
                .finishedAt(taskRecord.getFinishedAt())
                .build();
    }

    private StudentExportQuery normalizePayloadQuery(StudentExportTaskPayload payload) {
        StudentExportQuery query = normalizeQuery(payload.getQuery());
        if (query.getSnapshotVersion() == null) {
            query.setSnapshotVersion(payload.getSnapshotVersion());
        }
        if (query.getFormat() == null) {
            query.setFormat(payload.getFormat() == null ? StudentExportFormat.XLSX_SINGLE_SHEET : payload.getFormat());
        }
        return query;
    }

    private StudentExportFormat resolveExportFormat(StudentExportTaskPayload payload,
                                                    StudentExportTaskResult result,
                                                    StudentExportQuery query) {
        if (payload.getFormat() != null) {
            return payload.getFormat();
        }
        if (result.getFormat() != null) {
            return result.getFormat();
        }
        if (query.getFormat() != null) {
            return query.getFormat();
        }
        return StudentExportFormat.XLSX_SINGLE_SHEET;
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

    private boolean canRetry(AsyncTaskRecord taskRecord) {
        if (!AsyncTaskStatus.FAILED.name().equals(taskRecord.getStatus())
                && !AsyncTaskStatus.CANCELED.name().equals(taskRecord.getStatus())
                && !AsyncTaskStatus.EXPIRED.name().equals(taskRecord.getStatus())) {
            return false;
        }
        if (AsyncTaskStatus.FAILED.name().equals(taskRecord.getStatus())
                && Boolean.FALSE.equals(taskRecord.getRetryable())) {
            return false;
        }
        return safeInt(taskRecord.getRetryCount()) < safeInt(taskRecord.getMaxRetryCount());
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

    private int safeLongToInt(Long value) {
        if (value == null || value <= 0L) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : value.intValue();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
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
                .snapshotVersion(safeQuery.getSnapshotVersion())
                .format(safeQuery.getFormat() == null ? StudentExportFormat.XLSX_SINGLE_SHEET : safeQuery.getFormat())
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
