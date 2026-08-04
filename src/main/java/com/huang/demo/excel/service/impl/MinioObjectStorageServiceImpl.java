package com.huang.demo.excel.service.impl;

import com.huang.demo.excel.config.MinioProperties;
import com.huang.demo.excel.service.MinioObjectStorageService;
import io.minio.GetBucketLifecycleArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketLifecycleArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Expiration;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.LifecycleRule;
import io.minio.messages.ResponseDate;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class MinioObjectStorageServiceImpl implements MinioObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStorageServiceImpl.class);
    private static final String EXCEL_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String EXPORT_LIFECYCLE_RULE_ID = "student-excel-export-retention";

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public MinioObjectStorageServiceImpl(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @PostConstruct
    public void configureExportLifecycle() {
        if (!properties.isLifecycleEnabled()) {
            return;
        }
        try {
            List<LifecycleRule> rules = loadExistingLifecycleRules();
            for (Iterator<LifecycleRule> iterator = rules.iterator(); iterator.hasNext(); ) {
                LifecycleRule rule = iterator.next();
                if (EXPORT_LIFECYCLE_RULE_ID.equals(rule.id())) {
                    iterator.remove();
                }
            }
            int expireDays = Math.max(1, properties.getLifecycleExpireDays());
            String prefix = normalizePrefix(properties.getExportObjectPrefix()) + "/";
            rules.add(new LifecycleRule(
                    Status.ENABLED,
                    null,
                    new Expiration((ResponseDate) null, expireDays, null),
                    new RuleFilter(prefix),
                    EXPORT_LIFECYCLE_RULE_ID,
                    null,
                    null,
                    null));
            minioClient.setBucketLifecycle(SetBucketLifecycleArgs.builder()
                    .bucket(properties.getBucketName())
                    .config(new LifecycleConfiguration(rules))
                    .build());
            log.info("minio lifecycle configured, bucket={}, prefix={}, expireDays={}",
                    properties.getBucketName(), prefix, expireDays);
        } catch (Exception ex) {
            log.warn("configure minio lifecycle failed, bucket={}, prefix={}",
                    properties.getBucketName(), properties.getExportObjectPrefix(), ex);
        }
    }

    @Override
    public void uploadExcel(Path filePath, String objectKey) {
        try (InputStream inputStream = Files.newInputStream(filePath)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucketName())
                    .object(objectKey)
                    .stream(inputStream, Files.size(filePath), -1)
                    .contentType(EXCEL_CONTENT_TYPE)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("上传导出文件到 MinIO 失败", ex);
        }
    }

    @Override
    public String createDownloadUrl(String objectKey, String fileName) {
        try {
            int expireMinutes = Math.max(1, properties.getDownloadUrlExpireMinutes());
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.getBucketName())
                    .object(objectKey)
                    .expiry(expireMinutes, TimeUnit.MINUTES)
                    .extraQueryParams(Collections.singletonMap(
                            "response-content-disposition", "attachment; filename=\"" + fileName + "\""))
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("生成 MinIO 下载地址失败", ex);
        }
    }

    private List<LifecycleRule> loadExistingLifecycleRules() {
        try {
            LifecycleConfiguration configuration = minioClient.getBucketLifecycle(GetBucketLifecycleArgs.builder()
                    .bucket(properties.getBucketName())
                    .build());
            if (configuration == null || configuration.rules() == null) {
                return new ArrayList<LifecycleRule>();
            }
            return new ArrayList<LifecycleRule>(configuration.rules());
        } catch (ErrorResponseException ex) {
            String code = ex.errorResponse() == null ? null : ex.errorResponse().code();
            if ("NoSuchLifecycleConfiguration".equals(code)) {
                return new ArrayList<LifecycleRule>();
            }
            throw new IllegalStateException("读取 MinIO 生命周期配置失败", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("读取 MinIO 生命周期配置失败", ex);
        }
    }

    private String normalizePrefix(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return "excel/student";
        }
        return prefix.trim().replaceAll("^/+", "").replaceAll("/+$", "");
    }
}
