package com.huang.demo.excel.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.domain.model.StudentImportProgressCallback;
import com.huang.demo.excel.domain.model.StudentImportResult;
import com.huang.demo.excel.domain.model.StudentImportTaskPayload;
import com.huang.demo.excel.domain.model.StudentImportTaskResult;
import com.huang.demo.excel.service.StudentImportTaskService;
import com.huang.demo.excel.service.StudentService;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class StudentImportTaskServiceImpl implements StudentImportTaskService, TaskRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(StudentImportTaskServiceImpl.class);

    private final StudentService studentService;
    private final ExcelDemoProperties properties;
    private final ThreadPoolTaskExecutor importTaskExecutor;
    private final TaskCenterService taskCenterService;
    private final ObjectMapper objectMapper;

    public StudentImportTaskServiceImpl(StudentService studentService,
                                        ExcelDemoProperties properties,
                                        @Qualifier("importTaskExecutor") ThreadPoolTaskExecutor importTaskExecutor,
                                        TaskCenterService taskCenterService,
                                        ObjectMapper objectMapper) {
        this.studentService = studentService;
        this.properties = properties;
        this.importTaskExecutor = importTaskExecutor;
        this.taskCenterService = taskCenterService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initializeImportDirectory() {
        try {
            Files.createDirectories(getImportDirectory());
            cleanupExpiredTemporaryFiles();
        } catch (IOException ex) {
            log.warn("initialize import directory failed, path={}", getImportDirectory(), ex);
        }
    }

    @Override
    public AsyncTaskRecord submitImport(MultipartFile file, String ownerId) throws IOException {
        validateFile(file);
        String businessKey = UUID.randomUUID().toString().replace("-", "");
        Path temporaryFilePath = saveTemporaryFile(file, businessKey);
        StudentImportTaskPayload payload = StudentImportTaskPayload.builder()
                .originalName(normalizeOriginalName(file.getOriginalFilename()))
                .temporaryFilePath(temporaryFilePath.toString())
                .batchSize(Math.max(1, properties.getImportBatchSize()))
                .build();
        AsyncTaskRecord task;
        try {
            task = taskCenterService.createTask(CreateAsyncTaskCommand.builder()
                    .ownerId(ownerId)
                    .taskType(AsyncTaskType.IMPORT)
                    .taskName("学生数据导入")
                    .businessKey(businessKey)
                    .requestPayload(toJson(payload))
                    .build());
        } catch (RuntimeException ex) {
            deleteTemporaryFileQuietly(temporaryFilePath);
            throw ex;
        }
        try {
            submitExecution(task.getTaskId());
        } catch (RuntimeException ex) {
            task = taskCenterService.markFailed(task.getTaskId(), "导入任务提交失败");
            log.error("submit import task failed, taskId={}", task.getTaskId(), ex);
        }
        return task;
    }

    @Override
    public String taskType() {
        return AsyncTaskType.IMPORT.name();
    }

    @Override
    public AsyncTaskRecord retry(AsyncTaskRecord task, String ownerId) {
        AsyncTaskRecord retriedTask = taskCenterService.prepareRetry(task.getTaskId(), ownerId);
        try {
            submitExecution(retriedTask.getTaskId());
        } catch (RuntimeException ex) {
            retriedTask = taskCenterService.markFailed(retriedTask.getTaskId(), "导入任务提交失败");
            log.error("retry import task submit failed, taskId={}", retriedTask.getTaskId(), ex);
        }
        return retriedTask;
    }

    @Scheduled(fixedDelay = 3600000L, initialDelay = 3600000L)
    public void cleanupExpiredImportTemporaryFiles() {
        cleanupExpiredTemporaryFiles();
    }

    private void submitExecution(String taskId) {
        importTaskExecutor.execute(() -> executeImport(taskId));
    }

    private void executeImport(String taskId) {
        long start = System.currentTimeMillis();
        AsyncTaskRecord task = taskCenterService.markRunning(taskId);
        if (isCanceledOrExpired(task)) {
            return;
        }
        StudentImportTaskPayload payload = readPayload(task.getRequestPayload());
        Path temporaryFilePath = Paths.get(payload.getTemporaryFilePath());
        try (InputStream inputStream = Files.newInputStream(temporaryFilePath)) {
            ImportTaskProgressCallback callback = new ImportTaskProgressCallback(taskId);
            StudentImportResult result = studentService.importExcel(inputStream, payload.getBatchSize(), callback);
            AsyncTaskRecord completedTask = taskCenterService.markSuccess(taskId, toJson(StudentImportTaskResult.builder()
                    .importedCount(result.getImportedCount())
                    .batchCount(result.getBatchCount())
                    .build()));
            if (AsyncTaskStatus.SUCCESS.name().equals(completedTask.getStatus())) {
                Files.deleteIfExists(temporaryFilePath);
            }
            log.info("import task finished, taskId={}, imported={}, batchCount={}, elapsedMs={}",
                    taskId, result.getImportedCount(), result.getBatchCount(), System.currentTimeMillis() - start);
        } catch (TaskCanceledException ex) {
            log.info("import task canceled, taskId={}, elapsedMs={}", taskId, System.currentTimeMillis() - start);
        } catch (Exception ex) {
            taskCenterService.markFailed(taskId,
                    ex.getMessage() == null ? "导入失败，请查看服务端日志" : ex.getMessage());
            log.error("import task failed, taskId={}, elapsedMs={}", taskId, System.currentTimeMillis() - start, ex);
        }
    }

    private Path saveTemporaryFile(MultipartFile file, String businessKey) throws IOException {
        Files.createDirectories(getImportDirectory());
        String originalName = normalizeOriginalName(file.getOriginalFilename());
        String suffix = resolveSuffix(originalName);
        Path temporaryFilePath = getImportDirectory().resolve("student-import-" + businessKey + suffix);
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, temporaryFilePath, StandardCopyOption.REPLACE_EXISTING);
        }
        return temporaryFilePath;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("导入文件不能为空");
        }
        if (file.getSize() <= 0L) {
            throw new IllegalArgumentException("导入文件不能为空");
        }
    }

    private StudentImportTaskPayload readPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.trim().isEmpty()) {
            throw new IllegalStateException("导入任务上下文不存在");
        }
        try {
            return objectMapper.readValue(payloadJson, StudentImportTaskPayload.class);
        } catch (IOException ex) {
            throw new IllegalStateException("解析导入任务上下文失败", ex);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化导入任务上下文失败", ex);
        }
    }

    private boolean isCanceledOrExpired(AsyncTaskRecord task) {
        return AsyncTaskStatus.CANCELED.name().equals(task.getStatus())
                || AsyncTaskStatus.EXPIRED.name().equals(task.getStatus());
    }

    private Path getImportDirectory() {
        String configuredPath = properties.getImportTempDir();
        if (configuredPath == null || configuredPath.trim().isEmpty()) {
            configuredPath = System.getProperty("java.io.tmpdir") + "/student-excel-import";
        }
        return Paths.get(configuredPath);
    }

    private void cleanupExpiredTemporaryFiles() {
        Path importDirectory = getImportDirectory();
        if (!Files.isDirectory(importDirectory)) {
            return;
        }
        long retentionMillis = Math.max(1, properties.getExportFileRetentionHours()) * 60L * 60L * 1000L;
        long expireBefore = System.currentTimeMillis() - retentionMillis;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(importDirectory, "student-import-*")) {
            for (Path path : stream) {
                if (Files.isRegularFile(path) && Files.getLastModifiedTime(path).toMillis() < expireBefore) {
                    Files.deleteIfExists(path);
                    log.info("expired import temporary file deleted, filePath={}", path);
                }
            }
        } catch (IOException ex) {
            log.warn("cleanup expired import temporary files failed, path={}", importDirectory, ex);
        }
    }

    private void deleteTemporaryFileQuietly(Path temporaryFilePath) {
        if (temporaryFilePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryFilePath);
        } catch (IOException ex) {
            log.warn("delete import temporary file failed, filePath={}", temporaryFilePath, ex);
        }
    }

    private String normalizeOriginalName(String originalName) {
        if (originalName == null || originalName.trim().isEmpty()) {
            return "unknown.xlsx";
        }
        String normalized = originalName.trim().replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        if (slashIndex >= 0) {
            normalized = normalized.substring(slashIndex + 1);
        }
        normalized = normalized.replace("\u0000", "");
        return normalized.isEmpty() ? "unknown.xlsx" : normalized;
    }

    private String resolveSuffix(String originalName) {
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalName.length() - 1) {
            return ".xlsx";
        }
        String suffix = originalName.substring(dotIndex);
        if (suffix.length() > 16 || suffix.contains("/") || suffix.contains("\\")) {
            return ".xlsx";
        }
        return suffix;
    }

    private class ImportTaskProgressCallback implements StudentImportProgressCallback {

        private final String taskId;
        private final AtomicInteger parsedCount = new AtomicInteger();
        private final AtomicInteger importedCount = new AtomicInteger();

        private ImportTaskProgressCallback(String taskId) {
            this.taskId = taskId;
        }

        @Override
        public void onParsed(int parsedCount, int parsedBatchCount) {
            this.parsedCount.set(parsedCount);
            updateProgress();
        }

        @Override
        public void onCommitted(int importedCount, int importedBatchCount) {
            this.importedCount.set(importedCount);
            updateProgress();
        }

        @Override
        public void checkCanceled() {
            Optional<AsyncTaskRecord> latestTask = taskCenterService.findTask(taskId);
            if (!latestTask.isPresent()) {
                throw new TaskCanceledException("任务不存在");
            }
            AsyncTaskRecord task = latestTask.get();
            if (AsyncTaskStatus.CANCELED.name().equals(task.getStatus())) {
                throw new TaskCanceledException("任务已取消");
            }
            if (AsyncTaskStatus.EXPIRED.name().equals(task.getStatus())) {
                throw new TaskCanceledException("任务已过期");
            }
        }

        private void updateProgress() {
            int parsed = parsedCount.get();
            int imported = importedCount.get();
            int progressPercent = calculateProgressPercent(imported, parsed);
            taskCenterService.updateProgress(taskId, imported, parsed, progressPercent);
        }

        private int calculateProgressPercent(int imported, int parsed) {
            if (parsed <= 0) {
                return 0;
            }
            long progress = imported * 95L / parsed;
            return (int) Math.min(95L, Math.max(0L, progress));
        }
    }
}
