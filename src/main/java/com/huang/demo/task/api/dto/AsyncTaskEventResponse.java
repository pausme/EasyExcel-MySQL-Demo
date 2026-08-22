package com.huang.demo.task.api.dto;

import com.huang.demo.task.domain.entity.AsyncTaskEventLog;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class AsyncTaskEventResponse {

    private final String eventType;

    private final String message;

    private final Integer progressPercent;

    private final Long completedCount;

    private final Long totalCount;

    private final String failureType;

    private final String traceId;

    private final String workerId;

    private final LocalDateTime happenedAt;

    public static AsyncTaskEventResponse from(AsyncTaskEventLog eventLog) {
        return AsyncTaskEventResponse.builder()
                .eventType(eventLog.getEventType())
                .message(eventLog.getMessage())
                .progressPercent(eventLog.getProgressPercent())
                .completedCount(eventLog.getCompletedCount())
                .totalCount(eventLog.getTotalCount())
                .failureType(eventLog.getFailureType())
                .traceId(eventLog.getTraceId())
                .workerId(eventLog.getWorkerId())
                .happenedAt(eventLog.getCreatedAt())
                .build();
    }
}
