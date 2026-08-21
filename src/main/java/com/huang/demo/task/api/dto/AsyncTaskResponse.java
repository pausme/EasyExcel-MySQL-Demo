package com.huang.demo.task.api.dto;

import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.domain.model.AsyncTaskFailureType;
import com.huang.demo.task.domain.model.AsyncTaskStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Builder
public class AsyncTaskResponse {

    private final String taskId;

    private final String ownerId;

    private final String taskType;

    private final String taskName;

    private final String businessKey;

    private final AsyncTaskStatus status;

    private final Integer progressPercent;

    private final Long totalCount;

    private final Long completedCount;

    private final Integer retryCount;

    private final Integer maxRetryCount;

    private final String errorMessage;

    private final AsyncTaskFailureType failureType;

    private final Boolean retryable;

    private final String failureSuggestion;

    private final Boolean canRetry;

    private final Integer remainingRetryCount;

    private final Long durationMs;

    private final String workerId;

    private final LocalDateTime lastHeartbeatAt;

    private final List<AsyncTaskEventResponse> lifecycleEvents;

    private final LocalDateTime createdAt;

    private final LocalDateTime updatedAt;

    private final LocalDateTime startedAt;

    private final LocalDateTime finishedAt;

    private final LocalDateTime expireAt;

    public static AsyncTaskResponse from(AsyncTaskRecord record) {
        return AsyncTaskResponse.builder()
                .taskId(record.getTaskId())
                .ownerId(record.getOwnerId())
                .taskType(record.getTaskType())
                .taskName(record.getTaskName())
                .businessKey(record.getBusinessKey())
                .status(AsyncTaskStatus.valueOf(record.getStatus()))
                .progressPercent(record.getProgressPercent())
                .totalCount(record.getTotalCount())
                .completedCount(record.getCompletedCount())
                .retryCount(record.getRetryCount())
                .maxRetryCount(record.getMaxRetryCount())
                .errorMessage(record.getErrorMessage())
                .failureType(toFailureType(record.getFailureType()))
                .retryable(record.getRetryable())
                .failureSuggestion(record.getFailureSuggestion())
                .canRetry(canRetry(record))
                .remainingRetryCount(remainingRetryCount(record))
                .durationMs(durationMs(record))
                .workerId(record.getWorkerId())
                .lastHeartbeatAt(record.getLastHeartbeatAt())
                .lifecycleEvents(lifecycleEvents(record))
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .startedAt(record.getStartedAt())
                .finishedAt(record.getFinishedAt())
                .expireAt(record.getExpireAt())
                .build();
    }

    private static AsyncTaskFailureType toFailureType(String failureType) {
        if (failureType == null || failureType.trim().isEmpty()) {
            return null;
        }
        return AsyncTaskFailureType.valueOf(failureType);
    }

    private static boolean canRetry(AsyncTaskRecord record) {
        AsyncTaskStatus status = AsyncTaskStatus.valueOf(record.getStatus());
        if (status != AsyncTaskStatus.FAILED
                && status != AsyncTaskStatus.CANCELED
                && status != AsyncTaskStatus.EXPIRED) {
            return false;
        }
        if (status == AsyncTaskStatus.FAILED && Boolean.FALSE.equals(record.getRetryable())) {
            return false;
        }
        int retryCount = record.getRetryCount() == null ? 0 : record.getRetryCount();
        int maxRetryCount = record.getMaxRetryCount() == null ? 0 : record.getMaxRetryCount();
        return retryCount < maxRetryCount;
    }

    private static int remainingRetryCount(AsyncTaskRecord record) {
        int retryCount = record.getRetryCount() == null ? 0 : record.getRetryCount();
        int maxRetryCount = record.getMaxRetryCount() == null ? 0 : record.getMaxRetryCount();
        return Math.max(0, maxRetryCount - retryCount);
    }

    private static Long durationMs(AsyncTaskRecord record) {
        LocalDateTime start = record.getStartedAt();
        if (start == null) {
            start = record.getCreatedAt();
        }
        LocalDateTime end = record.getFinishedAt();
        if (end == null) {
            end = record.getUpdatedAt();
        }
        if (start == null || end == null || end.isBefore(start)) {
            return null;
        }
        return Duration.between(start, end).toMillis();
    }

    private static List<AsyncTaskEventResponse> lifecycleEvents(AsyncTaskRecord record) {
        List<AsyncTaskEventResponse> events = new ArrayList<AsyncTaskEventResponse>();
        addEvent(events, "CREATED", "任务已创建", record.getCreatedAt());
        addEvent(events, "RUNNING", "任务开始执行", record.getStartedAt());
        if (record.getLastHeartbeatAt() != null) {
            addEvent(events, "HEARTBEAT", "最近一次执行心跳", record.getLastHeartbeatAt());
        }
        AsyncTaskStatus status = AsyncTaskStatus.valueOf(record.getStatus());
        if (record.getFinishedAt() != null && status == AsyncTaskStatus.SUCCESS) {
            addEvent(events, "SUCCESS", "任务执行成功", record.getFinishedAt());
        } else if (record.getFinishedAt() != null && status == AsyncTaskStatus.FAILED) {
            addEvent(events, "FAILED", record.getErrorMessage(), record.getFinishedAt());
        } else if (record.getFinishedAt() != null && status == AsyncTaskStatus.CANCELED) {
            addEvent(events, "CANCELED", record.getErrorMessage(), record.getFinishedAt());
        } else if (record.getFinishedAt() != null && status == AsyncTaskStatus.EXPIRED) {
            addEvent(events, "EXPIRED", record.getErrorMessage(), record.getFinishedAt());
        }
        return events;
    }

    private static void addEvent(List<AsyncTaskEventResponse> events,
                                 String eventType,
                                 String message,
                                 LocalDateTime happenedAt) {
        if (happenedAt == null) {
            return;
        }
        events.add(AsyncTaskEventResponse.builder()
                .eventType(eventType)
                .message(message == null || message.trim().isEmpty() ? eventType : message)
                .happenedAt(happenedAt)
                .build());
    }
}
