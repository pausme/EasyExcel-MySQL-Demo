package com.huang.demo.common.idempotency.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.common.idempotency.domain.entity.IdempotencyRecord;
import com.huang.demo.common.idempotency.domain.model.IdempotencyStatus;
import com.huang.demo.common.idempotency.repository.IdempotencyRecordMapper;
import com.huang.demo.common.idempotency.service.IdempotencyService;
import com.huang.demo.common.idempotency.service.IdempotentAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyServiceImpl.class);
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final int MAX_OPERATION_LENGTH = 64;
    private static final int MAX_OWNER_ID_LENGTH = 64;
    private static final int DEFAULT_RETENTION_HOURS = 24;

    private final IdempotencyRecordMapper recordMapper;
    private final ObjectMapper objectMapper;

    public IdempotencyServiceImpl(IdempotencyRecordMapper recordMapper, ObjectMapper objectMapper) {
        this.recordMapper = recordMapper;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        recordMapper.createTableIfAbsent();
        log.info("idempotency record initialized");
    }

    @Override
    public <T> T execute(String ownerId,
                         String operation,
                         String idempotencyKey,
                         String requestFingerprint,
                         Class<T> responseType,
                         IdempotentAction<T> action) throws Exception {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        if (normalizedKey == null) {
            return action.execute();
        }
        String normalizedOwnerId = normalize(ownerId, MAX_OWNER_ID_LENGTH, "anonymous");
        String normalizedOperation = normalizeRequired(operation, MAX_OPERATION_LENGTH, "幂等操作类型不能为空");
        String normalizedFingerprint = normalizeRequired(requestFingerprint, 128, "幂等请求指纹不能为空");

        Optional<IdempotencyRecord> existing = recordMapper.findByKey(
                normalizedOwnerId, normalizedOperation, normalizedKey);
        if (existing.isPresent()) {
            return handleExisting(existing.get(), normalizedFingerprint, responseType);
        }

        IdempotencyRecord record = insertProcessing(normalizedOwnerId, normalizedOperation,
                normalizedKey, normalizedFingerprint);
        if (record == null) {
            IdempotencyRecord duplicate = recordMapper.findByKey(normalizedOwnerId, normalizedOperation, normalizedKey)
                    .orElseThrow(() -> new IllegalStateException("幂等记录创建冲突，请稍后重试"));
            return handleExisting(duplicate, normalizedFingerprint, responseType);
        }
        T response;
        try {
            response = action.execute();
            recordMapper.markSuccess(record.getId(), objectMapper.writeValueAsString(response), LocalDateTime.now());
            return response;
        } catch (Exception ex) {
            recordMapper.markFailed(record.getId(), safeErrorMessage(ex), LocalDateTime.now());
            throw ex;
        }
    }

    @Override
    public String fingerprint(Object... values) {
        StringBuilder builder = new StringBuilder();
        if (values != null) {
            for (Object value : values) {
                if (builder.length() > 0) {
                    builder.append('|');
                }
                builder.append(value == null ? "" : String.valueOf(value));
            }
        }
        return sha256(builder.toString());
    }

    private <T> T handleExisting(IdempotencyRecord record,
                                 String requestFingerprint,
                                 Class<T> responseType) throws Exception {
        if (!requestFingerprint.equals(record.getRequestFingerprint())) {
            throw new IllegalStateException("幂等键已用于不同请求，请更换 Idempotency-Key");
        }
        IdempotencyStatus status = IdempotencyStatus.valueOf(record.getStatus());
        if (status == IdempotencyStatus.SUCCESS) {
            return objectMapper.readValue(record.getResponsePayload(), responseType);
        }
        if (status == IdempotencyStatus.PROCESSING) {
            throw new IllegalStateException("幂等请求正在处理中，请稍后重试");
        }
        throw new IllegalStateException("上一次幂等请求执行失败，请更换 Idempotency-Key 后重试");
    }

    private IdempotencyRecord insertProcessing(String ownerId,
                                               String operation,
                                               String idempotencyKey,
                                               String requestFingerprint) {
        LocalDateTime now = LocalDateTime.now();
        IdempotencyRecord record = IdempotencyRecord.builder()
                .ownerId(ownerId)
                .operation(operation)
                .idempotencyKey(idempotencyKey)
                .requestFingerprint(requestFingerprint)
                .status(IdempotencyStatus.PROCESSING.name())
                .createdAt(now)
                .updatedAt(now)
                .expireAt(now.plusHours(DEFAULT_RETENTION_HOURS))
                .build();
        try {
            recordMapper.insert(record);
            return record;
        } catch (DuplicateKeyException ex) {
            return null;
        }
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return null;
        }
        String normalized = idempotencyKey.trim();
        if (normalized.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key 长度不能超过 " + MAX_IDEMPOTENCY_KEY_LENGTH);
        }
        return normalized;
    }

    private String normalizeRequired(String value, int maxLength, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return normalize(value, maxLength, null);
    }

    private String normalize(String value, int maxLength, String defaultValue) {
        String normalized = value;
        if (!StringUtils.hasText(normalized)) {
            normalized = defaultValue;
        }
        if (normalized == null) {
            return null;
        }
        normalized = normalized.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private String safeErrorMessage(Exception ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            message = ex.getClass().getSimpleName();
        }
        return message.length() > 512 ? message.substring(0, 512) : message;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                String hex = Integer.toHexString(b & 0xff);
                if (hex.length() == 1) {
                    builder.append('0');
                }
                builder.append(hex);
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 算法不可用", ex);
        }
    }
}
