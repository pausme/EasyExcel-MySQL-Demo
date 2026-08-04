package com.huang.demo.excel.service.impl;

import com.huang.demo.excel.config.MinioProperties;
import com.huang.demo.excel.service.MinioObjectStorageService;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Service
public class MinioObjectStorageServiceImpl implements MinioObjectStorageService {

    private static final String EXCEL_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public MinioObjectStorageServiceImpl(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
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
    public InputStream downloadExcel(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucketName())
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("下载 MinIO 导出文件失败", ex);
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
}
