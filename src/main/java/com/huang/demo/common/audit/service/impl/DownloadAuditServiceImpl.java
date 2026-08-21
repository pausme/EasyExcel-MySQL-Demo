package com.huang.demo.common.audit.service.impl;

import com.huang.demo.common.audit.domain.entity.DownloadAuditRecord;
import com.huang.demo.common.audit.repository.DownloadAuditRecordMapper;
import com.huang.demo.common.audit.service.DownloadAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class DownloadAuditServiceImpl implements DownloadAuditService {

    private static final Logger log = LoggerFactory.getLogger(DownloadAuditServiceImpl.class);

    private final DownloadAuditRecordMapper auditRecordMapper;

    public DownloadAuditServiceImpl(DownloadAuditRecordMapper auditRecordMapper) {
        this.auditRecordMapper = auditRecordMapper;
    }

    @PostConstruct
    public void init() {
        auditRecordMapper.createTableIfAbsent();
        log.info("download audit initialized");
    }

    @Override
    public void recordSignedDownload(String ownerId,
                                     String resourceType,
                                     String resourceId,
                                     String objectKey,
                                     String fileName,
                                     HttpServletRequest request) {
        try {
            auditRecordMapper.insert(DownloadAuditRecord.builder()
                    .auditId(UUID.randomUUID().toString().replace("-", ""))
                    .ownerId(normalize(ownerId, 64, "anonymous"))
                    .resourceType(normalize(resourceType, 32, "UNKNOWN"))
                    .resourceId(normalize(resourceId, 128, "UNKNOWN"))
                    .objectKey(normalize(objectKey, 512, null))
                    .fileName(normalize(fileName, 255, null))
                    .requestIp(resolveRequestIp(request))
                    .userAgent(normalize(request == null ? null : request.getHeader("User-Agent"), 512, null))
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (RuntimeException ex) {
            log.warn("record download audit failed, resourceType={}, resourceId={}", resourceType, resourceId, ex);
        }
    }

    private String resolveRequestIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.trim().isEmpty()) {
            String firstIp = forwardedFor.split(",")[0].trim();
            return normalize(firstIp, 64, null);
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.trim().isEmpty()) {
            return normalize(realIp, 64, null);
        }
        return normalize(request.getRemoteAddr(), 64, null);
    }

    private String normalize(String value, int maxLength, String defaultValue) {
        String normalized = value;
        if (normalized == null || normalized.trim().isEmpty()) {
            normalized = defaultValue;
        }
        if (normalized == null) {
            return null;
        }
        normalized = normalized.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }
}
