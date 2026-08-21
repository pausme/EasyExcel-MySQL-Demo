package com.huang.demo.excel.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.exception.ExcelAnalysisStopException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.excel.api.dto.ImportErrorPreviewResponse;
import com.huang.demo.excel.api.dto.ImportPrecheckResponse;
import com.huang.demo.excel.api.dto.ImportTaskResponse;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.config.MinioProperties;
import com.huang.demo.excel.domain.model.StudentImportProgressCallback;
import com.huang.demo.excel.domain.model.StudentImportResult;
import com.huang.demo.excel.domain.model.StudentImportTaskPayload;
import com.huang.demo.excel.domain.model.StudentImportTaskResult;
import com.huang.demo.excel.domain.model.StudentImportValidationException;
import com.huang.demo.excel.model.StudentExcelRow;
import com.huang.demo.excel.model.StudentImportErrorRow;
import com.huang.demo.excel.service.MinioObjectStorageService;
import com.huang.demo.excel.service.StudentImportTaskService;
import com.huang.demo.excel.service.StudentService;
import com.huang.demo.task.api.dto.AsyncTaskResponse;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class StudentImportTaskServiceImpl implements StudentImportTaskService, TaskRetryHandler, TaskRecoveryHandler {

    private static final Logger log = LoggerFactory.getLogger(StudentImportTaskServiceImpl.class);
    private static final int MIN_IMPORT_BATCH_SIZE = 500;
    private static final int MAX_IMPORT_BATCH_SIZE = 5000;
    private static final int DEFAULT_ERROR_PREVIEW_LIMIT = 20;
    private static final int MAX_ERROR_PREVIEW_LIMIT = 100;
    private static final int IMPORT_PRECHECK_PREVIEW_LIMIT = 100;
    private static final int MAX_XLSX_ENTRY_SCAN_COUNT = 512;
    private static final int XLSX_SCAN_BUFFER_SIZE = 8192;
    private static final String XLSX_CONTENT_TYPES_ENTRY = "[Content_Types].xml";
    private static final String XLSX_WORKBOOK_ENTRY = "xl/workbook.xml";

    private final StudentService studentService;
    private final ExcelDemoProperties properties;
    private final ThreadPoolTaskExecutor importTaskExecutor;
    private final TaskCenterService taskCenterService;
    private final ObjectMapper objectMapper;
    private final MinioObjectStorageService minioObjectStorageService;
    private final MinioProperties minioProperties;
    private final TaskExecutionGuard taskExecutionGuard;

    @Autowired
    public StudentImportTaskServiceImpl(StudentService studentService,
                                        ExcelDemoProperties properties,
                                        @Qualifier("importTaskExecutor") ThreadPoolTaskExecutor importTaskExecutor,
                                        TaskCenterService taskCenterService,
                                        ObjectMapper objectMapper,
                                        MinioObjectStorageService minioObjectStorageService,
                                        MinioProperties minioProperties,
                                        TaskExecutionGuard taskExecutionGuard) {
        this.studentService = studentService;
        this.properties = properties;
        this.importTaskExecutor = importTaskExecutor;
        this.taskCenterService = taskCenterService;
        this.objectMapper = objectMapper;
        this.minioObjectStorageService = minioObjectStorageService;
        this.minioProperties = minioProperties;
        this.taskExecutionGuard = taskExecutionGuard;
    }

    public StudentImportTaskServiceImpl(StudentService studentService,
                                        ExcelDemoProperties properties,
                                        ThreadPoolTaskExecutor importTaskExecutor,
                                        TaskCenterService taskCenterService,
                                        ObjectMapper objectMapper,
                                        MinioObjectStorageService minioObjectStorageService,
                                        MinioProperties minioProperties) {
        this(studentService, properties, importTaskExecutor, taskCenterService,
                objectMapper, minioObjectStorageService, minioProperties, new TaskExecutionGuard(taskCenterService));
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
        String originalName = normalizeOriginalName(file.getOriginalFilename());
        String sourceObjectKey = buildImportSourceObjectKey(businessKey, resolveSuffix(originalName));
        try (InputStream inputStream = file.getInputStream()) {
            minioObjectStorageService.uploadExcel(inputStream, file.getSize(), sourceObjectKey);
        }
        StudentImportTaskPayload payload = StudentImportTaskPayload.builder()
                .originalName(originalName)
                .sourceObjectKey(sourceObjectKey)
                .fileSize(file.getSize())
                .batchSize(getImportBatchSize())
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
            minioObjectStorageService.deleteQuietly(sourceObjectKey);
            throw ex;
        }
        try {
            submitExecution(task.getTaskId());
        } catch (RuntimeException ex) {
            task = taskCenterService.markFailed(retryableFailure(task.getTaskId(),
                    "导入任务提交失败", AsyncTaskFailureType.SYSTEM_ERROR, "导入任务未进入后台执行队列，可稍后重试"));
            log.error("submit import task failed, taskId={}", task.getTaskId(), ex);
        }
        return task;
    }

    @Override
    public ImportPrecheckResponse precheckImport(MultipartFile file) throws IOException {
        List<String> messages = new ArrayList<String>();
        List<StudentImportErrorRow> errorRows = new ArrayList<StudentImportErrorRow>();
        String originalName = file == null ? null : normalizeOriginalName(file.getOriginalFilename());
        Long fileSize = file == null ? null : file.getSize();
        Long dataRowCount = null;
        int maxRows = properties.getImportMaxRowsPerTask();
        long maxFileSize = properties.getImportMaxFileSizeForAsync();

        if (file == null || file.isEmpty() || file.getSize() <= 0L) {
            messages.add("导入文件不能为空");
            return buildPrecheckResponse(originalName, fileSize, dataRowCount, maxRows, maxFileSize, messages, errorRows);
        }
        if (maxFileSize > 0L && file.getSize() > maxFileSize) {
            messages.add("导入文件大小超过限制，maxBytes=" + maxFileSize + ", actualBytes=" + file.getSize());
        }
        if (!hasXlsxExtension(file.getOriginalFilename())) {
            messages.add("导入文件格式错误，请上传 .xlsx 文件");
            return buildPrecheckResponse(originalName, fileSize, dataRowCount, maxRows, maxFileSize, messages, errorRows);
        }

        XlsxInspectionResult inspectionResult;
        try (InputStream inputStream = file.getInputStream()) {
            inspectionResult = inspectXlsxPackage(inputStream);
        }
        if (!inspectionResult.isValidXlsx()) {
            messages.add("导入文件格式错误，请上传 .xlsx 文件");
            return buildPrecheckResponse(originalName, fileSize, dataRowCount, maxRows, maxFileSize, messages, errorRows);
        }
        dataRowCount = inspectionResult.getDataRowCount();
        if (maxRows > 0 && inspectionResult.getDataRowCount() > maxRows) {
            messages.add("导入文件数据行数超过限制，maxRows="
                    + maxRows + ", actualRows=" + inspectionResult.getDataRowCount());
        }

        try (InputStream inputStream = file.getInputStream()) {
            errorRows = previewImportRowErrors(inputStream, IMPORT_PRECHECK_PREVIEW_LIMIT);
        } catch (RuntimeException ex) {
            messages.add("导入文件内容解析失败，请确认文件表头和内容格式正确");
            log.warn("precheck import content failed, fileName={}", originalName, ex);
        }
        return buildPrecheckResponse(originalName, fileSize, dataRowCount, maxRows, maxFileSize, messages, errorRows);
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
            retriedTask = taskCenterService.markFailed(retryableFailure(retriedTask.getTaskId(),
                    "导入任务提交失败", AsyncTaskFailureType.SYSTEM_ERROR, "导入任务未进入后台执行队列，可稍后重试"));
            log.error("retry import task submit failed, taskId={}", retriedTask.getTaskId(), ex);
        }
        return retriedTask;
    }

    @Override
    public void recover(AsyncTaskRecord task) {
        if (!properties.isImportAutoRecoveryEnabled()) {
            taskCenterService.markFailed(retryableFailure(task.getTaskId(),
                    "导入任务执行节点异常退出，请重新提交导入任务",
                    AsyncTaskFailureType.SYSTEM_ERROR,
                    "当前未启用自动恢复，可手动重试任务"));
            log.warn("import task recovery disabled, taskId={}", task.getTaskId());
            return;
        }
        submitExecution(task.getTaskId());
    }

    @Override
    public Optional<ImportTaskResponse> findImportTask(String taskId, String ownerId) {
        return taskCenterService.findTask(taskId)
                .filter(task -> AsyncTaskType.IMPORT.name().equals(task.getTaskType()))
                .filter(task -> normalizeOwnerId(ownerId).equals(task.getOwnerId()))
                .map(task -> ImportTaskResponse.from(
                        AsyncTaskResponse.from(task), readResult(task.getResultPayload())));
    }

    @Override
    public Optional<ImportErrorPreviewResponse> previewImportErrors(String taskId, String ownerId, int limit) {
        int safeLimit = normalizeErrorPreviewLimit(limit);
        return taskCenterService.findTask(taskId)
                .filter(task -> AsyncTaskType.IMPORT.name().equals(task.getTaskType()))
                .filter(task -> normalizeOwnerId(ownerId).equals(task.getOwnerId()))
                .map(task -> ImportErrorPreviewResponse.from(
                        task.getTaskId(), readResult(task.getResultPayload()), safeLimit));
    }

    @Override
    public Optional<String> createErrorFileDownloadUrl(String taskId, String ownerId) {
        Optional<AsyncTaskRecord> taskOptional = taskCenterService.findTask(taskId)
                .filter(task -> AsyncTaskType.IMPORT.name().equals(task.getTaskType()))
                .filter(task -> normalizeOwnerId(ownerId).equals(task.getOwnerId()));
        if (!taskOptional.isPresent()) {
            return Optional.empty();
        }
        StudentImportTaskResult result = readResult(taskOptional.get().getResultPayload());
        if (result.getErrorObjectKey() == null || result.getErrorObjectKey().trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            minioObjectStorageService.ensureObjectExists(result.getErrorObjectKey());
            return Optional.of(minioObjectStorageService.createDownloadUrl(
                    result.getErrorObjectKey(), result.getErrorFileName()));
        } catch (RuntimeException ex) {
            if (isMissingImportErrorObject(ex)) {
                taskCenterService.markExpired(taskOptional.get().getTaskId(),
                        "导入错误文件不存在或已过期",
                        "导入错误文件已被清理，可重新提交导入任务");
            }
            log.warn("create import error file download url failed, taskId={}, objectKey={}",
                    taskOptional.get().getTaskId(), result.getErrorObjectKey(), ex);
            return Optional.empty();
        }
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
        Optional<TaskExecutionGuard.TaskExecutionLease> leaseOptional = taskExecutionGuard.tryStart(
                taskId, AsyncTaskType.IMPORT.name(), taskCenterService.currentWorkerId());
        if (!leaseOptional.isPresent()) {
            return;
        }
        try (TaskExecutionGuard.TaskExecutionLease lease = leaseOptional.get()) {
            AsyncTaskRecord task = lease.getTask();
            StudentImportTaskPayload payload = readPayload(task.getRequestPayload());
            try (InputStream inputStream = openImportInputStream(payload)) {
                ImportTaskProgressCallback callback = new ImportTaskProgressCallback(taskId);
                StudentImportResult result = studentService.importExcel(inputStream, payload.getBatchSize(), callback);
                AsyncTaskRecord completedTask = taskCenterService.markSuccess(taskId, toJson(StudentImportTaskResult.builder()
                        .importedCount(result.getImportedCount())
                        .batchCount(result.getBatchCount())
                        .build()));
                if (AsyncTaskStatus.SUCCESS.name().equals(completedTask.getStatus())) {
                    deleteLegacyTemporaryFile(payload);
                }
                log.info("import task finished, taskId={}, imported={}, batchCount={}, elapsedMs={}",
                        taskId, result.getImportedCount(), result.getBatchCount(), System.currentTimeMillis() - start);
            } catch (TaskCanceledException ex) {
                log.info("import task canceled, taskId={}, elapsedMs={}", taskId, System.currentTimeMillis() - start);
            } catch (StudentImportValidationException ex) {
                markValidationFailed(taskId, ex);
                log.info("import task validation failed, taskId={}, errorRows={}, elapsedMs={}",
                        taskId, ex.getErrorRows().size(), System.currentTimeMillis() - start);
            } catch (Exception ex) {
                taskCenterService.markFailed(retryableFailure(taskId,
                        ex.getMessage() == null ? "导入失败，请查看服务端日志" : ex.getMessage(),
                        classifySystemFailure(ex), "可稍后重试；若持续失败，请检查数据库、MinIO 或服务端日志"));
                log.error("import task failed, taskId={}, elapsedMs={}", taskId, System.currentTimeMillis() - start, ex);
            }
        }
    }

    private void markValidationFailed(String taskId, StudentImportValidationException exception) {
        try {
            StudentImportTaskResult result = writeAndUploadErrorFile(taskId, exception.getErrorRows());
            taskCenterService.markFailed(MarkAsyncTaskFailedCommand.builder()
                    .taskId(taskId)
                    .errorMessage(exception.getMessage())
                    .resultPayload(toJson(result))
                    .failureType(AsyncTaskFailureType.VALIDATION_ERROR)
                    .retryable(false)
                    .failureSuggestion("请下载错误明细，修正 Excel 后重新提交导入")
                    .build());
        } catch (RuntimeException ex) {
            taskCenterService.markFailed(MarkAsyncTaskFailedCommand.builder()
                    .taskId(taskId)
                    .errorMessage(exception.getMessage())
                    .failureType(AsyncTaskFailureType.VALIDATION_ERROR)
                    .retryable(false)
                    .failureSuggestion("请根据接口返回的错误信息修正 Excel 后重新提交导入")
                    .build());
            log.error("write import validation error file failed, taskId={}", taskId, ex);
        }
    }

    private MarkAsyncTaskFailedCommand retryableFailure(String taskId,
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

    private AsyncTaskFailureType classifySystemFailure(Exception ex) {
        String message = ex.getMessage();
        if (message != null && (message.contains("MinIO") || message.contains("文件不存在") || message.contains("已过期"))) {
            return AsyncTaskFailureType.DEPENDENCY_ERROR;
        }
        if (message != null && (message.contains("超过限制") || message.contains("线程池繁忙") || message.contains("超时"))) {
            return AsyncTaskFailureType.RESOURCE_LIMIT;
        }
        return AsyncTaskFailureType.SYSTEM_ERROR;
    }

    private boolean isMissingImportErrorObject(RuntimeException ex) {
        String message = ex.getMessage();
        return message != null && message.contains("MinIO 文件不存在或已过期");
    }

    private StudentImportTaskResult writeAndUploadErrorFile(String taskId, java.util.List<StudentImportErrorRow> errorRows) {
        String errorFileName = "student-import-error-" + taskId + ".xlsx";
        String errorObjectKey = buildImportErrorObjectKey(errorFileName);
        Path errorFilePath = getImportDirectory().resolve(errorFileName);
        try {
            Files.createDirectories(errorFilePath.getParent());
            EasyExcel.write(errorFilePath.toFile(), StudentImportErrorRow.class)
                    .sheet("错误明细")
                    .doWrite(errorRows);
            minioObjectStorageService.uploadExcel(errorFilePath, errorObjectKey);
            return StudentImportTaskResult.builder()
                    .errorCount(errorRows == null ? 0 : errorRows.size())
                    .errorFileName(errorFileName)
                    .errorObjectKey(errorObjectKey)
                    .errorSummary(buildErrorSummary(errorRows))
                    .errorPreviewRows(buildErrorPreviewRows(errorRows))
                    .build();
        } catch (IOException ex) {
            throw new IllegalStateException("创建导入错误文件失败", ex);
        } finally {
            deleteTemporaryFileQuietly(errorFilePath);
        }
    }

    private int normalizeErrorPreviewLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_ERROR_PREVIEW_LIMIT;
        }
        return Math.min(limit, MAX_ERROR_PREVIEW_LIMIT);
    }

    private Map<String, Integer> buildErrorSummary(List<StudentImportErrorRow> errorRows) {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        if (errorRows == null || errorRows.isEmpty()) {
            return result;
        }
        for (StudentImportErrorRow errorRow : errorRows) {
            String message = errorRow == null ? null : errorRow.getErrorMessage();
            if (message == null || message.trim().isEmpty()) {
                incrementErrorSummary(result, "UNKNOWN");
                continue;
            }
            String[] parts = message.split("[;；]");
            for (String part : parts) {
                String normalized = part == null ? "" : part.trim();
                incrementErrorSummary(result, normalized.isEmpty() ? "UNKNOWN" : normalized);
            }
        }
        return result;
    }

    private void incrementErrorSummary(Map<String, Integer> result, String errorType) {
        Integer count = result.get(errorType);
        result.put(errorType, count == null ? 1 : count + 1);
    }

    private List<StudentImportErrorRow> buildErrorPreviewRows(List<StudentImportErrorRow> errorRows) {
        if (errorRows == null || errorRows.isEmpty()) {
            return new ArrayList<StudentImportErrorRow>();
        }
        int previewSize = Math.min(errorRows.size(), MAX_ERROR_PREVIEW_LIMIT);
        return new ArrayList<StudentImportErrorRow>(errorRows.subList(0, previewSize));
    }

    private ImportPrecheckResponse buildPrecheckResponse(String originalName,
                                                         Long fileSize,
                                                         Long dataRowCount,
                                                         int maxRows,
                                                         long maxFileSize,
                                                         List<String> messages,
                                                         List<StudentImportErrorRow> errorRows) {
        List<StudentImportErrorRow> previewRows = buildErrorPreviewRows(errorRows);
        Map<String, Integer> errorSummary = buildErrorSummary(previewRows);
        List<String> safeMessages = messages == null ? new ArrayList<String>() : messages;
        for (String message : safeMessages) {
            incrementErrorSummary(errorSummary, message);
        }
        return ImportPrecheckResponse.of(
                safeMessages.isEmpty() && previewRows.isEmpty(),
                originalName,
                fileSize,
                dataRowCount,
                maxRows,
                maxFileSize,
                IMPORT_PRECHECK_PREVIEW_LIMIT,
                safeMessages,
                errorSummary,
                previewRows);
    }

    private List<StudentImportErrorRow> previewImportRowErrors(InputStream inputStream, int limit) {
        final List<StudentImportErrorRow> errorRows = new ArrayList<StudentImportErrorRow>();
        final Map<String, Integer> studentNoFirstRows = new HashMap<String, Integer>();
        try {
            EasyExcel.read(inputStream, StudentExcelRow.class, new AnalysisEventListener<StudentExcelRow>() {
                private int dataIndex = 0;

                @Override
                public void invoke(StudentExcelRow data, AnalysisContext context) {
                    dataIndex++;
                    int rowNo = dataIndex + 1;
                    StudentImportErrorRow errorRow = validatePreviewRow(data, rowNo, studentNoFirstRows);
                    if (errorRow != null) {
                        errorRows.add(errorRow);
                    }
                    if (dataIndex >= limit) {
                        throw new ExcelAnalysisStopException();
                    }
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    // no-op
                }
            }).sheet().doRead();
        } catch (ExcelAnalysisStopException ex) {
            return errorRows;
        }
        return errorRows;
    }

    private StudentImportErrorRow validatePreviewRow(StudentExcelRow row,
                                                     int rowNo,
                                                     Map<String, Integer> studentNoFirstRows) {
        StudentExcelRow safeRow = row == null ? new StudentExcelRow() : row;
        StringJoiner errors = new StringJoiner("; ");
        if (isBlank(safeRow.getStudentNo())) {
            errors.add("学号不能为空");
        } else if (safeRow.getStudentNo().length() > 32) {
            errors.add("学号长度不能超过32");
        } else {
            String normalizedStudentNo = safeRow.getStudentNo().trim();
            Integer firstRowNo = studentNoFirstRows.putIfAbsent(normalizedStudentNo, rowNo);
            if (firstRowNo != null) {
                errors.add("文件预览范围内学号重复，首次出现行号=" + firstRowNo);
            }
        }
        if (isBlank(safeRow.getName())) {
            errors.add("姓名不能为空");
        } else if (safeRow.getName().length() > 64) {
            errors.add("姓名长度不能超过64");
        }
        if (safeRow.getAge() != null && (safeRow.getAge() < 0 || safeRow.getAge() > 150)) {
            errors.add("年龄必须在0到150之间");
        }
        if (safeRow.getGender() != null && safeRow.getGender().length() > 16) {
            errors.add("性别长度不能超过16");
        }
        if (safeRow.getClassName() != null && safeRow.getClassName().length() > 64) {
            errors.add("班级长度不能超过64");
        }
        if (safeRow.getEmail() != null && safeRow.getEmail().length() > 128) {
            errors.add("邮箱长度不能超过128");
        } else if (!isBlank(safeRow.getEmail()) && !isSimpleEmail(safeRow.getEmail())) {
            errors.add("邮箱格式不正确");
        }
        if (safeRow.getBirthday() != null && safeRow.getBirthday().length() > 32) {
            errors.add("生日长度不能超过32");
        }
        String errorMessage = errors.toString();
        if (errorMessage.isEmpty()) {
            return null;
        }
        return StudentImportErrorRow.builder()
                .rowNo(rowNo)
                .studentNo(safeRow.getStudentNo())
                .name(safeRow.getName())
                .age(safeRow.getAge())
                .gender(safeRow.getGender())
                .className(safeRow.getClassName())
                .email(safeRow.getEmail())
                .birthday(safeRow.getBirthday())
                .errorMessage(errorMessage)
                .build();
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("导入文件不能为空");
        }
        if (file.getSize() <= 0L) {
            throw new IllegalArgumentException("导入文件不能为空");
        }
        long maxFileSize = properties.getImportMaxFileSizeForAsync();
        if (maxFileSize > 0L && file.getSize() > maxFileSize) {
            throw new IllegalArgumentException("导入文件大小超过限制，maxBytes="
                    + maxFileSize + ", actualBytes=" + file.getSize());
        }
        if (!hasXlsxExtension(file.getOriginalFilename())) {
            throw new IllegalArgumentException("导入文件格式错误，请上传 .xlsx 文件");
        }
        XlsxInspectionResult inspectionResult;
        try (InputStream inputStream = file.getInputStream()) {
            inspectionResult = inspectXlsxPackage(inputStream);
        } catch (IOException ex) {
            throw new IllegalArgumentException("导入文件格式错误，请上传 .xlsx 文件", ex);
        }
        if (!inspectionResult.isValidXlsx()) {
            throw new IllegalArgumentException("导入文件格式错误，请上传 .xlsx 文件");
        }
        int maxRows = properties.getImportMaxRowsPerTask();
        if (maxRows > 0 && inspectionResult.getDataRowCount() > maxRows) {
            throw new IllegalArgumentException("导入文件数据行数超过限制，maxRows="
                    + maxRows + ", actualRows=" + inspectionResult.getDataRowCount());
        }
    }

    private InputStream openImportInputStream(StudentImportTaskPayload payload) throws IOException {
        if (hasText(payload.getSourceObjectKey())) {
            try {
                return minioObjectStorageService.openObject(payload.getSourceObjectKey());
            } catch (RuntimeException ex) {
                throw new IllegalStateException("导入源文件不存在或已过期", ex);
            }
        }
        if (hasText(payload.getTemporaryFilePath())) {
            return Files.newInputStream(Paths.get(payload.getTemporaryFilePath()));
        }
        throw new IllegalStateException("导入源文件不存在或已过期");
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

    private StudentImportTaskResult readResult(String resultJson) {
        if (resultJson == null || resultJson.trim().isEmpty()) {
            return new StudentImportTaskResult();
        }
        try {
            return objectMapper.readValue(resultJson, StudentImportTaskResult.class);
        } catch (IOException ex) {
            log.warn("parse student import task result failed", ex);
            return new StudentImportTaskResult();
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

    private int getImportBatchSize() {
        int batchSize = properties.getImportBatchSize();
        if (batchSize < MIN_IMPORT_BATCH_SIZE) {
            return MIN_IMPORT_BATCH_SIZE;
        }
        return Math.min(batchSize, MAX_IMPORT_BATCH_SIZE);
    }

    private boolean hasXlsxExtension(String originalName) {
        return originalName != null && originalName.trim().toLowerCase(Locale.ROOT).endsWith(".xlsx");
    }

    private XlsxInspectionResult inspectXlsxPackage(InputStream inputStream) throws IOException {
        boolean hasContentTypes = false;
        boolean hasWorkbook = false;
        long dataRowCount = 0L;
        int scannedCount = 0;
        try (ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                scannedCount++;
                if (scannedCount > MAX_XLSX_ENTRY_SCAN_COUNT) {
                    return new XlsxInspectionResult(false, dataRowCount);
                }
                String entryName = entry.getName();
                if (XLSX_CONTENT_TYPES_ENTRY.equals(entryName)) {
                    hasContentTypes = true;
                } else if (XLSX_WORKBOOK_ENTRY.equals(entryName)) {
                    hasWorkbook = true;
                } else if (isWorksheetEntry(entryName)) {
                    long sheetRows = countWorksheetRows(zipInputStream);
                    if (sheetRows > 0) {
                        dataRowCount += Math.max(0L, sheetRows - 1L);
                    }
                }
            }
        }
        return new XlsxInspectionResult(hasContentTypes && hasWorkbook, dataRowCount);
    }

    private boolean isWorksheetEntry(String entryName) {
        return entryName != null
                && entryName.startsWith("xl/worksheets/")
                && entryName.endsWith(".xml");
    }

    private long countWorksheetRows(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[XLSX_SCAN_BUFFER_SIZE];
        String tail = "";
        long rowCount = 0L;
        int readCount;
        while ((readCount = inputStream.read(buffer)) >= 0) {
            String chunk = tail + new String(buffer, 0, readCount, StandardCharsets.UTF_8);
            rowCount += countToken(chunk, "<row");
            int tailLength = Math.min(3, chunk.length());
            tail = chunk.substring(chunk.length() - tailLength);
        }
        return rowCount;
    }

    private long countToken(String value, String token) {
        long count = 0L;
        int fromIndex = 0;
        while (true) {
            int index = value.indexOf(token, fromIndex);
            if (index < 0) {
                return count;
            }
            count++;
            fromIndex = index + token.length();
        }
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

    private void deleteLegacyTemporaryFile(StudentImportTaskPayload payload) {
        if (payload == null || !hasText(payload.getTemporaryFilePath())) {
            return;
        }
        deleteTemporaryFileQuietly(Paths.get(payload.getTemporaryFilePath()));
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

    private String buildImportErrorObjectKey(String fileName) {
        return normalizeObjectPrefix(
                minioProperties.getImportErrorObjectPrefix(), "excel/student/import-error") + "/" + fileName;
    }

    private String buildImportSourceObjectKey(String businessKey, String suffix) {
        return normalizeObjectPrefix(minioProperties.getImportSourceObjectPrefix(), "excel/student/import-source")
                + "/" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "/student-import-" + businessKey + suffix;
    }

    private String normalizeObjectPrefix(String prefix, String defaultPrefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return defaultPrefix;
        }
        return prefix.trim().replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isSimpleEmail(String value) {
        String trimmed = value == null ? "" : value.trim();
        int atIndex = trimmed.indexOf('@');
        int dotIndex = trimmed.lastIndexOf('.');
        return atIndex > 0 && dotIndex > atIndex + 1 && dotIndex < trimmed.length() - 1;
    }

    private String normalizeOwnerId(String ownerId) {
        if (ownerId == null || ownerId.trim().isEmpty()) {
            return "anonymous";
        }
        return ownerId.trim().length() > 64 ? ownerId.trim().substring(0, 64) : ownerId.trim();
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

    private static class XlsxInspectionResult {

        private final boolean validXlsx;
        private final long dataRowCount;

        private XlsxInspectionResult(boolean validXlsx, long dataRowCount) {
            this.validXlsx = validXlsx;
            this.dataRowCount = dataRowCount;
        }

        private boolean isValidXlsx() {
            return validXlsx;
        }

        private long getDataRowCount() {
            return dataRowCount;
        }
    }
}
