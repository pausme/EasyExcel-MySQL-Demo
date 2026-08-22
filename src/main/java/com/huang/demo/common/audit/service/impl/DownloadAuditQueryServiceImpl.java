package com.huang.demo.common.audit.service.impl;

import com.huang.demo.common.audit.api.dto.DownloadAuditPageQueryRequest;
import com.huang.demo.common.audit.api.dto.DownloadAuditPageResponse;
import com.huang.demo.common.audit.api.dto.DownloadAuditResponse;
import com.huang.demo.common.audit.domain.entity.DownloadAuditRecord;
import com.huang.demo.common.audit.repository.DownloadAuditRecordMapper;
import com.huang.demo.common.audit.service.DownloadAuditQueryService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class DownloadAuditQueryServiceImpl implements DownloadAuditQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final DownloadAuditRecordMapper auditRecordMapper;

    public DownloadAuditQueryServiceImpl(DownloadAuditRecordMapper auditRecordMapper) {
        this.auditRecordMapper = auditRecordMapper;
    }

    @Override
    public DownloadAuditPageResponse page(String currentOwnerId, boolean admin, DownloadAuditPageQueryRequest request) {
        DownloadAuditPageQueryRequest safeRequest = request == null ? new DownloadAuditPageQueryRequest() : request;
        int pageNo = normalizePageNo(safeRequest.getPageNo());
        int pageSize = normalizePageSize(safeRequest.getPageSize());
        String ownerId = admin
                ? normalizeOptionalText(safeRequest.getOwnerId(), 64)
                : normalizeRequiredOwnerId(currentOwnerId);
        String resourceType = normalizeOptionalText(safeRequest.getResourceType(), 32);
        String resourceId = normalizeOptionalText(safeRequest.getResourceId(), 128);
        validateTimeRange(safeRequest.getCreatedFrom(), safeRequest.getCreatedTo());
        int offset = (pageNo - 1) * pageSize;

        long total = auditRecordMapper.countPage(
                ownerId, resourceType, resourceId, safeRequest.getCreatedFrom(), safeRequest.getCreatedTo());
        List<DownloadAuditRecord> records = auditRecordMapper.listPage(
                ownerId, resourceType, resourceId, safeRequest.getCreatedFrom(), safeRequest.getCreatedTo(),
                offset, pageSize);
        List<DownloadAuditResponse> responses = new ArrayList<DownloadAuditResponse>(records.size());
        for (DownloadAuditRecord record : records) {
            responses.add(DownloadAuditResponse.from(record));
        }
        return DownloadAuditPageResponse.builder()
                .total(total)
                .pageNo(pageNo)
                .pageSize(pageSize)
                .records(responses)
                .build();
    }

    private String normalizeRequiredOwnerId(String ownerId) {
        String normalized = normalizeOptionalText(ownerId, 64);
        return normalized == null ? "anonymous" : normalized;
    }

    private String normalizeOptionalText(String value, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private void validateTimeRange(LocalDateTime createdFrom, LocalDateTime createdTo) {
        if (createdFrom != null && createdTo != null && createdTo.isBefore(createdFrom)) {
            throw new IllegalArgumentException("下载审计时间范围不正确");
        }
    }

    private int normalizePageNo(Integer pageNo) {
        if (pageNo == null || pageNo < 1) {
            return 1;
        }
        return pageNo;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
