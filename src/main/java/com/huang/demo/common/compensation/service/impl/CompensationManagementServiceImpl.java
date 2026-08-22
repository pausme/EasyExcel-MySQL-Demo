package com.huang.demo.common.compensation.service.impl;

import com.huang.demo.common.compensation.api.dto.CompensationActionResponse;
import com.huang.demo.common.compensation.api.dto.CompensationPageQueryRequest;
import com.huang.demo.common.compensation.api.dto.CompensationPageResponse;
import com.huang.demo.common.compensation.api.dto.CompensationResponse;
import com.huang.demo.common.compensation.domain.entity.CompensationRecord;
import com.huang.demo.common.compensation.domain.model.CompensationStatus;
import com.huang.demo.common.compensation.repository.CompensationRecordMapper;
import com.huang.demo.common.compensation.service.CompensationManagementService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class CompensationManagementServiceImpl implements CompensationManagementService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final CompensationRecordMapper recordMapper;

    public CompensationManagementServiceImpl(CompensationRecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    @Override
    public CompensationPageResponse page(CompensationPageQueryRequest request) {
        CompensationPageQueryRequest safeRequest = request == null ? new CompensationPageQueryRequest() : request;
        int pageNo = normalizePageNo(safeRequest.getPageNo());
        int pageSize = normalizePageSize(safeRequest.getPageSize());
        String bizType = normalizeOptionalText(safeRequest.getBizType(), 64);
        String bizId = normalizeOptionalText(safeRequest.getBizId(), 128);
        String failureType = normalizeOptionalText(safeRequest.getFailureType(), 64);
        List<String> statuses = normalizeStatuses(safeRequest);
        validateTimeRange(safeRequest.getCreatedFrom(), safeRequest.getCreatedTo());
        int offset = (pageNo - 1) * pageSize;

        long total = recordMapper.countPage(
                bizType, bizId, failureType, statuses, safeRequest.getCreatedFrom(), safeRequest.getCreatedTo());
        List<CompensationRecord> records = recordMapper.listPage(
                bizType, bizId, failureType, statuses, safeRequest.getCreatedFrom(), safeRequest.getCreatedTo(),
                offset, pageSize);
        List<CompensationResponse> responses = new ArrayList<CompensationResponse>(records.size());
        for (CompensationRecord record : records) {
            responses.add(CompensationResponse.from(record));
        }
        return CompensationPageResponse.builder()
                .total(total)
                .pageNo(pageNo)
                .pageSize(pageSize)
                .records(responses)
                .build();
    }

    @Override
    public CompensationActionResponse retry(String compensationId) {
        String normalizedId = normalizeRequiredText(compensationId, 64, "补偿记录 ID 不能为空");
        LocalDateTime now = LocalDateTime.now();
        int updated = recordMapper.markPendingForRetry(normalizedId, now, now);
        return CompensationActionResponse.builder()
                .compensationId(normalizedId)
                .status(CompensationStatus.PENDING.name())
                .updated(updated > 0)
                .build();
    }

    @Override
    public CompensationActionResponse ignore(String compensationId) {
        String normalizedId = normalizeRequiredText(compensationId, 64, "补偿记录 ID 不能为空");
        int updated = recordMapper.markIgnored(normalizedId, LocalDateTime.now());
        return CompensationActionResponse.builder()
                .compensationId(normalizedId)
                .status(CompensationStatus.IGNORED.name())
                .updated(updated > 0)
                .build();
    }

    private List<String> normalizeStatuses(CompensationPageQueryRequest request) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        String single = normalizeOptionalStatus(request.getStatus());
        if (single != null) {
            result.add(single);
        }
        if (request.getStatuses() != null) {
            for (String status : request.getStatuses()) {
                String normalized = normalizeOptionalStatus(status);
                if (normalized != null) {
                    result.add(normalized);
                }
            }
        }
        return new ArrayList<String>(result);
    }

    private String normalizeOptionalStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        CompensationStatus.valueOf(normalized);
        return normalized;
    }

    private String normalizeOptionalText(String value, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private String normalizeRequiredText(String value, int maxLength, String message) {
        String normalized = normalizeOptionalText(value, maxLength);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private void validateTimeRange(LocalDateTime createdFrom, LocalDateTime createdTo) {
        if (createdFrom != null && createdTo != null && createdTo.isBefore(createdFrom)) {
            throw new IllegalArgumentException("补偿记录创建时间范围不正确");
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
