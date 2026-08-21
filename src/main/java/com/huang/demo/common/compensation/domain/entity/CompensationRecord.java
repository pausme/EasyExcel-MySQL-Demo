package com.huang.demo.common.compensation.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompensationRecord {

    private Long id;

    private String compensationId;

    private String bizType;

    private String bizId;

    private String failureType;

    private String status;

    private Integer retryCount;

    private Integer maxRetryCount;

    private LocalDateTime nextRetryAt;

    private String payload;

    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;
}
