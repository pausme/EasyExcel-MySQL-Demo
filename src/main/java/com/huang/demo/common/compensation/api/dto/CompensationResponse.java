package com.huang.demo.common.compensation.api.dto;

import com.huang.demo.common.compensation.domain.entity.CompensationRecord;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class CompensationResponse {

    private final String compensationId;

    private final String bizType;

    private final String bizId;

    private final String failureType;

    private final String status;

    private final Integer retryCount;

    private final Integer maxRetryCount;

    private final LocalDateTime nextRetryAt;

    private final String payload;

    private final String lastError;

    private final LocalDateTime createdAt;

    private final LocalDateTime updatedAt;

    private final LocalDateTime completedAt;

    public static CompensationResponse from(CompensationRecord record) {
        return CompensationResponse.builder()
                .compensationId(record.getCompensationId())
                .bizType(record.getBizType())
                .bizId(record.getBizId())
                .failureType(record.getFailureType())
                .status(record.getStatus())
                .retryCount(record.getRetryCount())
                .maxRetryCount(record.getMaxRetryCount())
                .nextRetryAt(record.getNextRetryAt())
                .payload(record.getPayload())
                .lastError(record.getLastError())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .completedAt(record.getCompletedAt())
                .build();
    }
}
