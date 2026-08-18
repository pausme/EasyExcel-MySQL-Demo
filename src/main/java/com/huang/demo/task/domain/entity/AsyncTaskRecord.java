package com.huang.demo.task.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncTaskRecord {

    private Long id;

    private String taskId;

    private String ownerId;

    private String taskType;

    private String taskName;

    private String businessKey;

    private String status;

    private Integer progressPercent;

    private Long totalCount;

    private Long completedCount;

    private Integer retryCount;

    private Integer maxRetryCount;

    private String requestPayload;

    private String resultPayload;

    private String errorMessage;

    private String failureType;

    private Boolean retryable;

    private String failureSuggestion;

    private String workerId;

    private LocalDateTime lastHeartbeatAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime expireAt;
}
