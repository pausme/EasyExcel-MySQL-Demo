package com.huang.demo.task.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.task.api.dto.AsyncTaskPageQueryRequest;
import com.huang.demo.task.api.dto.AsyncTaskPageResponse;
import com.huang.demo.task.api.dto.AsyncTaskResponse;
import com.huang.demo.task.config.TaskCenterProperties;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.domain.model.AsyncTaskStatus;
import com.huang.demo.task.domain.model.AsyncTaskType;
import com.huang.demo.task.domain.model.CreateAsyncTaskCommand;
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

    public TaskCenterServiceImpl(AsyncTaskRecordMapper taskRecordMapper,
                                 StringRedisTemplate stringRedisTemplate,
                                 ObjectMapper objectMapper,
                                 TaskCenterProperties properties) {
        this.taskRecordMapper = taskRecordMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
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
        AsyncTaskRecord record = findTaskRequired(taskId);
        record = refreshExpiredIfNecessary(record);
        if (isTerminalStatus(record.getStatus())) {
            return record;
        }
        LocalDateTime now = LocalDateTime.now();
        record.setStatus(AsyncTaskStatus.RUNNING.name());
        if (record.getStartedAt() == null) {
            record.setStartedAt(now);
        }
        record.setUpdatedAt(now);
        updateRequired(record);
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
        record.setUpdatedAt(LocalDateTime.now());
        updateRequired(record);
        return record;
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
        record.setUpdatedAt(now);
        record.setFinishedAt(now);
        updateRequired(record);
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AsyncTaskRecord markFailed(String taskId, String errorMessage) {
        AsyncTaskRecord record = findTaskRequired(taskId);
        record = refreshExpiredIfNecessary(record);
        if (isTerminalStatus(record.getStatus())) {
            return record;
        }
        LocalDateTime now = LocalDateTime.now();
        record.setStatus(AsyncTaskStatus.FAILED.name());
        record.setErrorMessage(normalizeErrorMessage(errorMessage));
        record.setUpdatedAt(now);
        record.setFinishedAt(now);
        updateRequired(record);
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
        updateRequired(record);
        return true;
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
        LocalDateTime now = LocalDateTime.now();
        record.setStatus(AsyncTaskStatus.CREATED.name());
        record.setProgressPercent(0);
        record.setTotalCount(0L);
        record.setCompletedCount(0L);
        record.setRetryCount(retryCount + 1);
        record.setMaxRetryCount(maxRetryCount);
        record.setResultPayload(null);
        record.setErrorMessage(null);
        record.setStartedAt(null);
        record.setFinishedAt(null);
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
}
