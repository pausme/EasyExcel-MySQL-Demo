package com.huang.demo.common.compensation.service.impl;

import com.huang.demo.common.compensation.domain.entity.CompensationRecord;
import com.huang.demo.common.compensation.domain.model.CompensationStatus;
import com.huang.demo.common.compensation.repository.CompensationRecordMapper;
import com.huang.demo.common.compensation.service.CompensationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class CompensationServiceImpl implements CompensationService {

    private static final Logger log = LoggerFactory.getLogger(CompensationServiceImpl.class);
    private static final int MAX_BIZ_TYPE_LENGTH = 64;
    private static final int MAX_BIZ_ID_LENGTH = 128;
    private static final int MAX_FAILURE_TYPE_LENGTH = 64;
    private static final int MAX_PAYLOAD_LENGTH = 4096;
    private static final int MAX_ERROR_LENGTH = 1024;
    private static final int DEFAULT_MAX_RETRY_COUNT = 5;

    private final CompensationRecordMapper recordMapper;

    public CompensationServiceImpl(CompensationRecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    @PostConstruct
    public void init() {
        recordMapper.createTableIfAbsent();
        log.info("compensation record initialized");
    }

    @Override
    public CompensationRecord recordPending(String bizType,
                                            String bizId,
                                            String failureType,
                                            String payload) {
        String normalizedBizType = normalizeRequired(bizType, MAX_BIZ_TYPE_LENGTH, "补偿业务类型不能为空");
        String normalizedBizId = normalizeRequired(bizId, MAX_BIZ_ID_LENGTH, "补偿业务标识不能为空");
        String normalizedFailureType = normalizeRequired(failureType, MAX_FAILURE_TYPE_LENGTH, "补偿失败类型不能为空");
        try {
            Optional<CompensationRecord> active = recordMapper.findActive(
                    normalizedBizType, normalizedBizId, normalizedFailureType);
            if (active.isPresent()) {
                return active.get();
            }
            LocalDateTime now = LocalDateTime.now();
            CompensationRecord record = CompensationRecord.builder()
                    .compensationId(UUID.randomUUID().toString().replace("-", ""))
                    .bizType(normalizedBizType)
                    .bizId(normalizedBizId)
                    .failureType(normalizedFailureType)
                    .status(CompensationStatus.PENDING.name())
                    .retryCount(0)
                    .maxRetryCount(DEFAULT_MAX_RETRY_COUNT)
                    .nextRetryAt(now)
                    .payload(normalize(payload, MAX_PAYLOAD_LENGTH))
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            recordMapper.insert(record);
            return record;
        } catch (DuplicateKeyException ex) {
            return recordMapper.findActive(normalizedBizType, normalizedBizId, normalizedFailureType)
                    .orElseThrow(() -> new IllegalStateException("补偿记录创建冲突，请稍后重试", ex));
        } catch (RuntimeException ex) {
            log.warn("record compensation failed, bizType={}, bizId={}, failureType={}",
                    normalizedBizType, normalizedBizId, normalizedFailureType, ex);
            return null;
        }
    }

    @Override
    public void markRunning(String compensationId) {
        updateQuietly(() -> recordMapper.markRunning(normalizeId(compensationId), LocalDateTime.now()),
                "mark compensation running");
    }

    @Override
    public void markSuccess(String compensationId) {
        updateQuietly(() -> recordMapper.markSuccess(normalizeId(compensationId), LocalDateTime.now()),
                "mark compensation success");
    }

    @Override
    public void markFailed(String compensationId, String lastError) {
        LocalDateTime now = LocalDateTime.now();
        updateQuietly(() -> recordMapper.markFailed(
                        normalizeId(compensationId),
                        normalize(lastError, MAX_ERROR_LENGTH),
                        now.plusMinutes(5),
                        now),
                "mark compensation failed");
    }

    private void updateQuietly(UpdateAction action, String operation) {
        try {
            if (action.update() == 0) {
                log.warn("{} skipped, compensation record not found or already terminal", operation);
            }
        } catch (RuntimeException ex) {
            log.warn("{} failed", operation, ex);
        }
    }

    private String normalizeId(String value) {
        return normalizeRequired(value, 64, "补偿记录 ID 不能为空");
    }

    private String normalizeRequired(String value, int maxLength, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return normalize(value, maxLength);
    }

    private String normalize(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private interface UpdateAction {
        int update();
    }
}
