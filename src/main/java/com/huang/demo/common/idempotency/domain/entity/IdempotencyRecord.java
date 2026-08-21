package com.huang.demo.common.idempotency.domain.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class IdempotencyRecord {

    private Long id;

    private String ownerId;

    private String operation;

    private String idempotencyKey;

    private String requestFingerprint;

    private String status;

    private String responsePayload;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime expireAt;
}
