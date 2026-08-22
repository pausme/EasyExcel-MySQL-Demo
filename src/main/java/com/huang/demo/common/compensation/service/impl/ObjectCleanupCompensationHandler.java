package com.huang.demo.common.compensation.service.impl;

import com.huang.demo.common.compensation.domain.entity.CompensationRecord;
import com.huang.demo.common.compensation.domain.model.CompensationFailureType;
import com.huang.demo.common.compensation.service.CompensationHandler;
import com.huang.demo.excel.service.MinioObjectStorageService;
import com.huang.demo.file.service.FileObjectStorageService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class ObjectCleanupCompensationHandler implements CompensationHandler {

    private final MinioObjectStorageService minioObjectStorageService;
    private final FileObjectStorageService fileObjectStorageService;

    public ObjectCleanupCompensationHandler(MinioObjectStorageService minioObjectStorageService,
                                            FileObjectStorageService fileObjectStorageService) {
        this.minioObjectStorageService = minioObjectStorageService;
        this.fileObjectStorageService = fileObjectStorageService;
    }

    @Override
    public boolean supports(CompensationRecord record) {
        if (record == null || !StringUtils.hasText(extractObjectKey(record.getPayload()))) {
            return false;
        }
        String failureType = record.getFailureType();
        return CompensationFailureType.CLEANUP_OBJECT_FAILED.name().equals(failureType)
                || CompensationFailureType.ORPHAN_OBJECT.name().equals(failureType);
    }

    @Override
    public void handle(CompensationRecord record) {
        String objectKey = extractObjectKey(record.getPayload());
        if (!StringUtils.hasText(objectKey)) {
            throw new IllegalArgumentException("补偿记录缺少 objectKey");
        }
        if (isFileCenterRecord(record)) {
            cleanupFileCenterObject(objectKey);
        } else {
            minioObjectStorageService.deleteQuietly(objectKey);
        }
    }

    private boolean isFileCenterRecord(CompensationRecord record) {
        String bizType = record.getBizType();
        return "FILE".equals(bizType) || "FILE_UPLOAD".equals(bizType) || "FILE_STORAGE".equals(bizType);
    }

    private void cleanupFileCenterObject(String objectKey) {
        if (looksLikePrefix(objectKey)) {
            List<String> objectKeys = fileObjectStorageService.listObjectKeys(objectKey);
            fileObjectStorageService.deleteQuietly(objectKeys);
        }
        fileObjectStorageService.deleteQuietly(objectKey);
    }

    private boolean looksLikePrefix(String objectKey) {
        return objectKey.endsWith("/") || "files/multipart".equals(objectKey)
                || objectKey.contains("/multipart/") || objectKey.contains("/parts/");
    }

    private String extractObjectKey(String payload) {
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        String normalized = payload.trim();
        String marker = "objectKey=";
        int start = normalized.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = normalized.indexOf(',', start);
        String objectKey = end < 0 ? normalized.substring(start) : normalized.substring(start, end);
        objectKey = objectKey.trim();
        return objectKey.isEmpty() || "null".equalsIgnoreCase(objectKey) ? null : objectKey;
    }
}
