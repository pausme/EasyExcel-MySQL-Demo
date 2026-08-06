package com.huang.demo.task.api.dto;

import com.huang.demo.task.domain.entity.AsyncTaskRecord;
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
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .startedAt(record.getStartedAt())
                .finishedAt(record.getFinishedAt())
                .expireAt(record.getExpireAt())
                .build();
    }
}
