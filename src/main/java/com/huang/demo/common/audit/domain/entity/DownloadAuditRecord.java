package com.huang.demo.common.audit.domain.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DownloadAuditRecord {

    private Long id;

    private String auditId;

    private String ownerId;

    private String resourceType;

    private String resourceId;

    private String objectKey;

    private String fileName;

    private String requestIp;

    private String userAgent;

    private LocalDateTime createdAt;
}
