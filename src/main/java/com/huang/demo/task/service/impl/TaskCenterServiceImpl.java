package com.huang.demo.task.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.task.api.dto.AsyncTaskPageQueryRequest;
import com.huang.demo.task.api.dto.AsyncTaskPageResponse;
import com.huang.demo.task.api.dto.AsyncTaskResponse;
import com.huang.demo.task.config.TaskCenterProperties;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.domain.model.AsyncTaskFailureType;
import com.huang.demo.task.domain.model.AsyncTaskStatus;
import com.huang.demo.task.domain.model.AsyncTaskType;
import com.huang.demo.task.domain.model.CreateAsyncTaskCommand;
import com.huang.demo.task.domain.model.MarkAsyncTaskFailedCommand;
import com.huang.demo.task.monitor.TaskMetricsService;
import com.huang.demo.task.repository.AsyncTaskRecordMapper;
import com.huang.demo.task.service.TaskCenterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TaskCenterServiceImpl implements TaskCenterService {

    private static final Logger log = LoggerFactory.getLogger(TaskCenterServiceImpl.class);

    private final AsyncTaskRecordMapper taskRecordMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final TaskCenterProperties properties;
    private final TaskMetricsService taskMetricsService;
    private final String workerId;

    public TaskCenterServiceImpl(AsyncTaskRecordMapper taskRecordMapper,
                                 StringRedisTemplate stringRedisTemplate,
                                 ObjectMapper objectMapper,
                                 TaskCenterProperties properties,
                                 TaskMetricsService taskMetricsService) {
        this.taskRecordMapper = taskRecordMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.taskMetricsService = taskMetricsService;
        this.workerId = resolveWorkerId(properties);
    }

    @PostConstruct
    public void init() {
        if (!properties.isInitEnabled()) {
            log.info("task center database initialization skipped");
            return;
        }
        taskRecordMapper.createTableIfAbsent();
        log.info("task center initialized");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AsyncTaskRecord createTask(CreateAsyncTaskCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("任务创建参数不能为空");
        }
        validateTaskSubmissionQuota(normalizeOwnerId(command.getOwnerId()));
        LocalDateTime now = LocalDateTime.now();
        AsyncTaskRecord record = AsyncTaskRecord.builder()
                .taskId(UUID.randomUUID().toString().replace("-", ""))
                .ownerId(normalizeOwnerId(command.getOwnerId()))
                .taskType(normalizeTaskType(command.getTaskType() == null ? null : command.getTaskType().name()))
                .taskName(normalizeTaskName(command.getTaskName()))
                .businessKey(normalizeBusinessKey(command.getBusinessKey()))
                .status(AsyncTaskStatus.CREATED.name())
                .progressPercent(0)
                .totalCount(0L)
                .completedCount(0L)
                .retryCount(0)
                .maxRetryCount(normalizeMaxRetryCount(command.getMaxRetryCount()))
                .requestPayload(command.getRequestPayload())
                .createdAt(now)
                .updatedAt(now)
                .expireAt(now.plusHours(Math.max(1, properties.getCacheRetentionHours())))
                .build();
        taskRecordMapper.insert(record);
        cacheTaskQuietly(record);
        taskMetricsService.recordSubmitted(record);
        return record;
    }

    @Override
    public Optional<AsyncTaskRecord> findTask(String taskId) {
        String normalizedTaskId = normalizeTaskId(taskId);
        Optional<AsyncTaskRecord> cachedTask = findTaskFromCache(normalizedTaskId);
        if (cachedTask.isPresent()) {
            return Optional.of(refreshExpiredIfNecessary(cachedTask.get()));
        }
        Optional<AsyncTaskRecord> taskOptional = taskRecordMapper.findByTaskId(normalizedTaskId);
        if (taskOptional.isPresent()) {
            AsyncTaskRecord record = refreshExpiredIfNecessary(taskOptional.get());
            cacheTaskQuietly(record);
            return Optional.of(record);
        }
        return Optional.empty();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AsyncTaskRecord markRunning(String taskId) {
        return markRunning(taskId, workerId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AsyncTaskRecord markRunning(String taskId, String workerId) {
        AsyncTaskRecord record = findTaskRequired(taskId);
        record = refreshExpiredIfNecessary(record);
        if (isTerminalStatus(record.getStatus())) {
            return record;
        }
        LocalDateTime now = LocalDateTime.now();
        record.setStatus(AsyncTaskStatus.RUNNING.name());
        record.setFailureType(null);
        record.setRetryable(null);
        record.setFailureSuggestion(null);
        record.setWorkerId(normalizeWorkerId(workerId));
        record.setLastHeartbeatAt(now);
        if (record.getStartedAt() == null) {
            record.setStartedAt(now);
        }
        record.setUpdatedAt(now);
        updateRequired(record);
        taskMetricsService.recordStatusChanged(record);
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AsyncTaskRecord updateProgress(String taskId, long completedCount, long totalCount, int progressPercent) {
        AsyncTaskRecord record = findTaskRequired(taskId);
        record = refreshExpiredIfNecessary(record);
        if (isTerminalStatus(record.getStatus())) {
            return record;
        }
        record.setCompletedCount(Math.max(0L, completedCount));
        record.setTotalCount(Math.max(0L, totalCount));
        record.setProgressPercent(normalizeProgress(progressPercent));
        LocalDateTime now = LocalDateTime.now();
        record.setWorkerId(workerId);
        record.setLastHeartbeatAt(now);
        record.setUpdatedAt(now);
        updateRequired(record);
        return record;
    }

    @Override
    public void heartbeat(String taskId, String workerId) {
        taskRecordMapper.updateHeartbeat(normalizeTaskId(taskId), normalizeWorkerId(workerId), LocalDateTime.now());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AsyncTaskRecord markSuccess(String taskId, String resultPayload) {
        AsyncTaskRecord record = findTaskRequired(taskId);
        record = refreshExpiredIfNecessary(record);
        if (isTerminalStatus(record.getStatus())) {
            return record;
        }
        LocalDateTime now = LocalDateTime.now();
        record.setStatus(AsyncTaskStatus.SUCCESS.name());
        record.setProgressPercent(100);
        record.setResultPayload(resultPayload);
        record.setErrorMessage(null);
        record.setFailureType(null);
        record.setRetryable(null);
        record.setFailureSuggestion(null);
        record.setLastHeartbeatAt(now);
        record.setUpdatedAt(now);
        record.setFinishedAt(now);
        updateRequired(record);
        taskMetricsService.recordStatusChanged(record);
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AsyncTaskRecord markFailed(String taskId, String errorMessage) {
        return markFailed(MarkAsyncTaskFailedCommand.builder()
                .taskId(taskId)
                .errorMessage(errorMessage)
                .failureType(AsyncTaskFailureType.SYSTEM_ERROR)
                .retryable(true)
                .failureSuggestion("可稍后重试；若持续失败，请查看服务端日志")
                .build());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AsyncTaskRecord markFailed(String taskId, String errorMessage, String resultPayload) {
        return markFailed(MarkAsyncTaskFailedCommand.builder()
                .taskId(taskId)
                .errorMessage(errorMessage)
                .resultPayload(resultPayload)
                .failureType(AsyncTaskFailureType.SYSTEM_ERROR)
                .retryable(true)
                .failureSuggestion("可稍后重试；若持续失败，请查看服务端日志")
                .build());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AsyncTaskRecord markFailed(MarkAsyncTaskFailedCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("任务失败参数不能为空");
        }
        AsyncTaskRecord record = findTaskRequired(command.getTaskId());
        record = refreshExpiredIfNecessary(record);
        if (isTerminalStatus(record.getStatus())) {
            return record;
        }
        LocalDateTime now = LocalDateTime.now();
        record.setStatus(AsyncTaskStatus.FAILED.name());
        record.setErrorMessage(normalizeErrorMessage(command.getErrorMessage()));
        record.setResultPayload(command.getResultPayload());
        record.setFailureType(normalizeFailureType(command.getFailureType()));
        record.setRetryable(normalizeRetryable(command.getRetryable(), command.getFailureType()));
        record.setFailureSuggestion(normalizeFailureSuggestion(command.getFailureSuggestion()));
        record.setLastHeartbeatAt(now);
        record.setUpdatedAt(now);
        record.setFinishedAt(now);
        updateRequired(record);
        taskMetricsService.recordStatusChanged(record);
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AsyncTaskRecord markExpired(String taskId, String errorMessage, String failureSuggestion) {
        AsyncTaskRecord record = findTaskRequired(taskId);
        record = refreshExpiredIfNecessary(record);
        if (AsyncTaskStatus.FAILED.name().equals(record.getStatus())
                || AsyncTaskStatus.CANCELED.name().equals(record.getStatus())
                || AsyncTaskStatus.EXPIRED.name().equals(record.getStatus())) {
            return record;
        }
        LocalDateTime now = LocalDateTime.now();
        record.setStatus(AsyncTaskStatus.EXPIRED.name());
        record.setErrorMessage(normalizeErrorMessage(errorMessage));
        record.setFailureType(AsyncTaskFailureType.DEPENDENCY_ERROR.name());
        record.setRetryable(true);
        record.setFailureSuggestion(normalizeFailureSuggestion(failureSuggestion));
        record.setLastHeartbeatAt(now);
        record.setUpdatedAt(now);
        record.setFinishedAt(now);
        updateRequired(record);
        taskMetricsService.recordStatusChanged(record);
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelTask(String taskId, String ownerId) {
        AsyncTaskRecord record = findTaskRequired(taskId);
        assertOwner(record, ownerId);
        record = refreshExpiredIfNecessary(record);
        if (isTerminalStatus(record.getStatus())) {
            return AsyncTaskStatus.CANCELED.name().equals(record.getStatus());
        }
        LocalDateTime now = LocalDateTime.now();
        record.setStatus(AsyncTaskStatus.CANCELED.name());
        record.setUpdatedAt(now);
        record.setFinishedAt(now);
        record.setErrorMessage("任务已取消");
        record.setFailureType(AsyncTaskFailureType.CANCELED.name());
        record.setRetryable(true);
        record.setFailureSuggestion("如需继续处理，可重新发起重试");
        record.setLastHeartbeatAt(now);
        updateRequired(record);
        taskMetricsService.recordStatusChanged(record);
        return true;
    }

    @Override
    public long countActiveTasks() {
        return taskRecordMapper.countActive();
    }

    @Override
    public long countActiveTasksByOwner(String ownerId) {
        return taskRecordMapper.countActiveByOwner(normalizeOwnerId(ownerId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AsyncTaskRecord prepareRetry(String taskId, String ownerId) {
        AsyncTaskRecord record = findTaskRequired(taskId);
        assertOwner(record, ownerId);
        record = refreshExpiredIfNecessary(record);
        if (!AsyncTaskStatus.FAILED.name().equals(record.getStatus())
                && !AsyncTaskStatus.CANCELED.name().equals(record.getStatus())
                && !AsyncTaskStatus.EXPIRED.name().equals(record.getStatus())) {
            throw new IllegalStateException("当前任务状态不允许重试，status=" + record.getStatus());
        }
        int retryCount = safeInt(record.getRetryCount());
        int maxRetryCount = normalizeMaxRetryCount(record.getMaxRetryCount());
        if (retryCount >= maxRetryCount) {
            throw new IllegalStateException("任务重试次数已达上限，retryCount=" + retryCount
                    + ", maxRetryCount=" + maxRetryCount);
        }
        if (AsyncTaskStatus.FAILED.name().equals(record.getStatus())
                && Boolean.FALSE.equals(record.getRetryable())) {
            throw new IllegalStateException("当前任务失败类型不允许重试，failureType=" + record.getFailureType());
        }
        LocalDateTime now = LocalDateTime.now();
        record.setStatus(AsyncTaskStatus.CREATED.name());
        record.setProgressPercent(0);
        record.setTotalCount(0L);
        record.setCompletedCount(0L);
        record.setRetryCount(retryCount + 1);
        record.setMaxRetryCount(maxRetryCount);
        record.setResultPayload(null);
        record.setErrorMessage(null);
        record.setFailureType(null);
        record.setRetryable(null);
        record.setFailureSuggestion(null);
        record.setStartedAt(null);
        record.setFinishedAt(null);
        record.setWorkerId(null);
        record.setLastHeartbeatAt(null);
        record.setUpdatedAt(now);
        record.setExpireAt(now.plusHours(Math.max(1, properties.getCacheRetentionHours())));
        updateRequired(record);
        return record;
    }

    @Override
    public AsyncTaskPageResponse pageMyTasks(String ownerId, AsyncTaskPageQueryRequest request) {
        AsyncTaskPageQueryRequest safeRequest = request == null ? new AsyncTaskPageQueryRequest() : request;
        String normalizedOwnerId = normalizeOwnerId(ownerId);
        int pageNo = normalizePageNo(safeRequest.getPageNo());
        int pageSize = normalizePageSize(safeRequest.getPageSize());
        String taskType = normalizeOptionalTaskType(safeRequest.getTaskType());
        String status = normalizeOptionalStatus(safeRequest.getStatus());
        int offset = (pageNo - 1) * pageSize;

        long total = taskRecordMapper.countByOwner(normalizedOwnerId, taskType, status);
        List<AsyncTaskRecord> records = taskRecordMapper.listByOwnerPage(
                normalizedOwnerId, taskType, status, offset, pageSize);
        List<AsyncTaskResponse> responses = new ArrayList<AsyncTaskResponse>(records.size());
        for (AsyncTaskRecord record : records) {
            responses.add(AsyncTaskResponse.from(refreshExpiredIfNecessary(record)));
        }
        return AsyncTaskPageResponse.builder()
                .total(total)
                .pageNo(pageNo)
                .pageSize(pageSize)
                .records(responses)
                .build();
    }

    @Override
    public AsyncTaskPageResponse pageMyTasksByBusinessKey(String ownerId,
                                                          String taskType,
                                                          String businessKey,
                                                          AsyncTaskPageQueryRequest request) {
        AsyncTaskPageQueryRequest safeRequest = request == null ? new AsyncTaskPageQueryRequest() : request;
        String normalizedOwnerId = normalizeOwnerId(ownerId);
        String normalizedBusinessKey = normalizeBusinessKeyRequired(businessKey);
        int pageNo = normalizePageNo(safeRequest.getPageNo());
        int pageSize = normalizePageSize(safeRequest.getPageSize());
        String normalizedTaskType = normalizeOptionalTaskType(taskType);
        String status = normalizeOptionalStatus(safeRequest.getStatus());
        int offset = (pageNo - 1) * pageSize;

        long total = taskRecordMapper.countByOwnerAndBusinessKey(
                normalizedOwnerId, normalizedTaskType, normalizedBusinessKey, status);
        List<AsyncTaskRecord> records = taskRecordMapper.listByOwnerAndBusinessKeyPage(
                normalizedOwnerId, normalizedTaskType, normalizedBusinessKey, status, offset, pageSize);
        List<AsyncTaskResponse> responses = new ArrayList<AsyncTaskResponse>(records.size());
        for (AsyncTaskRecord record : records) {
            responses.add(AsyncTaskResponse.from(refreshExpiredIfNecessary(record)));
        }
        return AsyncTaskPageResponse.builder()
                .total(total)
                .pageNo(pageNo)
                .pageSize(pageSize)
                .records(responses)
                .build();
    }

    @Override
    public List<AsyncTaskRecord> listRecoverableTasks(int limit) {
        if (!properties.isRecoveryEnabled()) {
            return new ArrayList<AsyncTaskRecord>();
        }
        int safeLimit = Math.max(1, Math.min(limit, Math.max(1, properties.getRecoveryBatchSize())));
        return taskRecordMapper.listRecoverable(resolveHeartbeatBefore(), safeLimit);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean claimRecoverableTask(String taskId) {
        if (!properties.isRecoveryEnabled()) {
            return false;
        }
        int claimed = taskRecordMapper.claimRecoverable(
                normalizeTaskId(taskId), workerId, resolveHeartbeatBefore());
        if (claimed > 0) {
            Optional<AsyncTaskRecord> recordOptional = taskRecordMapper.findByTaskId(normalizeTaskId(taskId));
            if (recordOptional.isPresent()) {
                cacheTaskQuietly(recordOptional.get());
            }
        }
        return claimed > 0;
    }

    @Override
    public String currentWorkerId() {
        return workerId;
    }

    @Scheduled(fixedDelay = 3600000L, initialDelay = 3600000L)
    public void expireStaleTasks() {
        try {
            int expired = taskRecordMapper.markExpiredBefore(LocalDateTime.now());
            if (expired > 0) {
                log.info("expired async tasks marked, count={}", expired);
            }
        } catch (RuntimeException ex) {
            log.warn("expire async tasks failed", ex);
        }
    }

    private Optional<AsyncTaskRecord> findTaskFromCache(String taskId) {
        try {
            String json = stringRedisTemplate.opsForValue().get(buildTaskCacheKey(taskId));
            if (json == null || json.trim().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, AsyncTaskRecord.class));
        } catch (IOException ex) {
            log.warn("parse async task from redis failed, taskId={}", taskId, ex);
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("read async task from redis failed, taskId={}", taskId, ex);
            return Optional.empty();
        }
    }

    private AsyncTaskRecord findTaskRequired(String taskId) {
        return findTask(normalizeTaskId(taskId))
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
    }

    private AsyncTaskRecord refreshExpiredIfNecessary(AsyncTaskRecord record) {
        if (!isActiveStatus(record.getStatus()) || record.getExpireAt() == null) {
            return record;
        }
        if (record.getExpireAt().isAfter(LocalDateTime.now())) {
            return record;
        }
        LocalDateTime now = LocalDateTime.now();
        record.setStatus(AsyncTaskStatus.EXPIRED.name());
        record.setUpdatedAt(now);
        record.setFinishedAt(now);
        updateRequired(record);
        taskMetricsService.recordStatusChanged(record);
        return record;
    }

    private void updateRequired(AsyncTaskRecord record) {
        int updated = taskRecordMapper.update(record);
        if (updated == 0) {
            throw new IllegalStateException("任务状态更新失败，taskId=" + record.getTaskId());
        }
        cacheTaskQuietly(record);
    }

    private void cacheTaskQuietly(AsyncTaskRecord record) {
        try {
            stringRedisTemplate.opsForValue().set(
                    buildTaskCacheKey(record.getTaskId()),
                    objectMapper.writeValueAsString(record),
                    calculateCacheTtl(record));
        } catch (JsonProcessingException ex) {
            log.warn("serialize async task failed, taskId={}", record.getTaskId(), ex);
        } catch (RuntimeException ex) {
            log.warn("save async task to redis failed, taskId={}", record.getTaskId(), ex);
        }
    }

    private Duration calculateCacheTtl(AsyncTaskRecord record) {
        LocalDateTime expireAt = record.getExpireAt();
        if (expireAt == null) {
            return Duration.ofHours(Math.max(1, properties.getCacheRetentionHours()));
        }
        long seconds = Duration.between(LocalDateTime.now(), expireAt).getSeconds();
        return Duration.ofSeconds(Math.max(60L, seconds));
    }

    private String buildTaskCacheKey(String taskId) {
        String prefix = properties.getRedisKeyPrefix();
        if (prefix == null || prefix.trim().isEmpty()) {
            prefix = "task:center:";
        }
        return prefix + taskId;
    }

    private void assertOwner(AsyncTaskRecord record, String ownerId) {
        if (!normalizeOwnerId(ownerId).equals(record.getOwnerId())) {
            throw new IllegalArgumentException("任务不存在");
        }
    }

    private void validateTaskSubmissionQuota(String ownerId) {
        int maxActiveTasksPerOwner = Math.max(0, properties.getMaxActiveTasksPerOwner());
        if (maxActiveTasksPerOwner > 0 && countActiveTasksByOwner(ownerId) >= maxActiveTasksPerOwner) {
            throw new IllegalStateException("当前用户的活跃任务过多，请稍后重试");
        }
        int maxActiveTasksTotal = Math.max(0, properties.getMaxActiveTasksTotal());
        if (maxActiveTasksTotal > 0 && countActiveTasks() >= maxActiveTasksTotal) {
            throw new IllegalStateException("系统活跃任务过多，请稍后重试");
        }
    }

    private boolean isActiveStatus(String status) {
        return AsyncTaskStatus.CREATED.name().equals(status) || AsyncTaskStatus.RUNNING.name().equals(status);
    }

    private boolean isTerminalStatus(String status) {
        return AsyncTaskStatus.SUCCESS.name().equals(status)
                || AsyncTaskStatus.FAILED.name().equals(status)
                || AsyncTaskStatus.CANCELED.name().equals(status)
                || AsyncTaskStatus.EXPIRED.name().equals(status);
    }

    private String normalizeTaskId(String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            throw new IllegalArgumentException("任务 ID 不能为空");
        }
        return taskId.trim();
    }

    private String normalizeOwnerId(String ownerId) {
        String normalized = ownerId;
        if (normalized == null || normalized.trim().isEmpty()) {
            normalized = properties.getDefaultOwnerId();
        }
        normalized = normalized == null ? "anonymous" : normalized.trim();
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private String normalizeTaskType(String taskType) {
        if (taskType == null || taskType.trim().isEmpty()) {
            throw new IllegalArgumentException("任务类型不能为空");
        }
        String normalized = taskType.trim().toUpperCase();
        AsyncTaskType.valueOf(normalized);
        return normalized;
    }

    private String normalizeOptionalTaskType(String taskType) {
        if (taskType == null || taskType.trim().isEmpty()) {
            return null;
        }
        return normalizeTaskType(taskType);
    }

    private String normalizeOptionalStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        AsyncTaskStatus.valueOf(normalized);
        return normalized;
    }

    private String normalizeTaskName(String taskName) {
        if (taskName == null || taskName.trim().isEmpty()) {
            return "异步任务";
        }
        String normalized = taskName.trim();
        return normalized.length() > 128 ? normalized.substring(0, 128) : normalized;
    }

    private String normalizeBusinessKey(String businessKey) {
        if (businessKey == null || businessKey.trim().isEmpty()) {
            return null;
        }
        String normalized = businessKey.trim();
        return normalized.length() > 128 ? normalized.substring(0, 128) : normalized;
    }

    private String normalizeBusinessKeyRequired(String businessKey) {
        String normalized = normalizeBusinessKey(businessKey);
        if (normalized == null) {
            throw new IllegalArgumentException("业务标识不能为空");
        }
        return normalized;
    }

    private LocalDateTime resolveHeartbeatBefore() {
        int timeoutSeconds = Math.max(30, properties.getRecoveryHeartbeatTimeoutSeconds());
        return LocalDateTime.now().minusSeconds(timeoutSeconds);
    }

    private String resolveWorkerId(TaskCenterProperties properties) {
        String configured = properties.getWorkerId();
        if (configured != null && !configured.trim().isEmpty()) {
            String normalized = configured.trim();
            return normalized.length() > 128 ? normalized.substring(0, 128) : normalized;
        }
        return "worker-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String normalizeWorkerId(String value) {
        String normalized = value == null || value.trim().isEmpty() ? workerId : value.trim();
        return normalized.length() > 128 ? normalized.substring(0, 128) : normalized;
    }

    private int normalizeMaxRetryCount(Integer maxRetryCount) {
        int configured = maxRetryCount == null ? properties.getMaxRetryCount() : maxRetryCount;
        return Math.max(0, configured);
    }

    private int normalizeProgress(int progressPercent) {
        return Math.max(0, Math.min(100, progressPercent));
    }

    private int normalizePageNo(Integer pageNo) {
        if (pageNo == null || pageNo < 1) {
            return 1;
        }
        return pageNo;
    }

    private int normalizePageSize(Integer pageSize) {
        int configuredMaxPageSize = Math.max(1, properties.getMaxPageSize());
        if (pageSize == null || pageSize < 1) {
            return Math.min(20, configuredMaxPageSize);
        }
        return Math.min(pageSize, configuredMaxPageSize);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String normalizeErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.trim().isEmpty()) {
            return "任务执行失败，请查看服务端日志";
        }
        String normalized = errorMessage.trim();
        return normalized.length() > 1024 ? normalized.substring(0, 1024) : normalized;
    }

    private String normalizeFailureType(AsyncTaskFailureType failureType) {
        if (failureType == null) {
            return AsyncTaskFailureType.SYSTEM_ERROR.name();
        }
        return failureType.name();
    }

    private Boolean normalizeRetryable(Boolean retryable, AsyncTaskFailureType failureType) {
        if (retryable != null) {
            return retryable;
        }
        if (failureType == AsyncTaskFailureType.VALIDATION_ERROR
                || failureType == AsyncTaskFailureType.RESOURCE_LIMIT
                || failureType == AsyncTaskFailureType.CANCELED) {
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }

    private String normalizeFailureSuggestion(String suggestion) {
        if (suggestion == null || suggestion.trim().isEmpty()) {
            return null;
        }
        String normalized = suggestion.trim();
        return normalized.length() > 512 ? normalized.substring(0, 512) : normalized;
    }
}
