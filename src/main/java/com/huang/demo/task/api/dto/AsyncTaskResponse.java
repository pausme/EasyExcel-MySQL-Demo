package com.huang.demo.task.api.dto;

import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.domain.model.AsyncTaskFailureType;
import com.huang.demo.task.domain.model.AsyncTaskStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

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
}
