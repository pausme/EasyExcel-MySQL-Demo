package com.huang.demo.common.audit.api.dto;

import com.huang.demo.common.audit.domain.entity.DownloadAuditRecord;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class DownloadAuditResponse {

    private final String auditId;

    private final String ownerId;

    private final String resourceType;

    private final String resourceId;

    private final String objectKey;

    private final String fileName;

    private final String requestIp;

    private final String userAgent;

    private final LocalDateTime createdAt;

    public static DownloadAuditResponse from(DownloadAuditRecord record) {
        return DownloadAuditResponse.builder()
                .auditId(record.getAuditId())
                .ownerId(record.getOwnerId())
                .resourceType(record.getResourceType())
                .resourceId(record.getResourceId())
                .objectKey(record.getObjectKey())
                .fileName(record.getFileName())
                .requestIp(record.getRequestIp())
                .userAgent(record.getUserAgent())
                .createdAt(record.getCreatedAt())
                .build();
    }
}
