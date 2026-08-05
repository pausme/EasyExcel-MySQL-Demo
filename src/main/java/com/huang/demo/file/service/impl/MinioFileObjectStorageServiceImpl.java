package com.huang.demo.file.service.impl;

import com.huang.demo.excel.config.MinioProperties;
import com.huang.demo.file.config.FileCenterProperties;
import com.huang.demo.file.domain.model.StoredFile;
import com.huang.demo.file.domain.model.StoredObject;
import com.huang.demo.file.service.FileObjectStorageService;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class MinioFileObjectStorageServiceImpl implements FileObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioFileObjectStorageServiceImpl.class);
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final FileCenterProperties fileCenterProperties;

    public MinioFileObjectStorageServiceImpl(MinioClient minioClient,
                                             MinioProperties minioProperties,
                                             FileCenterProperties fileCenterProperties) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
        this.fileCenterProperties = fileCenterProperties;
    }

    @Override
    public String bucketName() {
        return minioProperties.getBucketName();
    }

    @Override
    public StoredFile upload(InputStream inputStream, long fileSize, String objectKey, String contentType) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(minioProperties.getBucketName())
                        .object(objectKey)
                        .stream(digestInputStream, fileSize, -1)
                        .contentType(normalizeContentType(contentType))
                        .build());
            }
            return StoredFile.builder()
                    .bucketName(minioProperties.getBucketName())
                    .objectKey(objectKey)
                    .fileMd5(toHex(digest.digest()))
                    .fileSize(fileSize)
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException("上传文件到 MinIO 失败", ex);
        }
    }

    @Override
    public String createUploadUrl(String objectKey) {
        try {
            int expireMinutes = Math.max(1, fileCenterProperties.getUploadUrlExpireMinutes());
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .expiry(expireMinutes, TimeUnit.MINUTES)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("生成文件上传地址失败", ex);
        }
    }

    @Override
    public String createDownloadUrl(String objectKey, String fileName) {
        try {
            int expireMinutes = Math.max(1, fileCenterProperties.getDownloadUrlExpireMinutes());
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .expiry(expireMinutes, TimeUnit.MINUTES)
                    .extraQueryParams(Collections.singletonMap(
                            "response-content-disposition", "attachment; filename=\"" + sanitizeFileName(fileName) + "\""))
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("生成文件下载地址失败", ex);
        }
    }

    @Override
    public StoredObject statObject(String objectKey) {
        try {
            StatObjectResponse response = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .build());
            return StoredObject.builder()
                    .objectKey(objectKey)
                    .contentType(response.contentType())
                    .size(response.size())
                    .etag(response.etag())
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException("读取文件对象信息失败", ex);
        }
    }

    @Override
    public void composeObject(String objectKey, List<String> sourceObjectKeys, String contentType) {
        if (sourceObjectKeys == null || sourceObjectKeys.isEmpty()) {
            throw new IllegalArgumentException("待合并分片不能为空");
        }
        try {
            List<ComposeSource> sources = new ArrayList<ComposeSource>(sourceObjectKeys.size());
            for (String sourceObjectKey : sourceObjectKeys) {
                sources.add(ComposeSource.builder()
                        .bucket(minioProperties.getBucketName())
                        .object(sourceObjectKey)
                        .build());
            }
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Content-Type", normalizeContentType(contentType));
            minioClient.composeObject(ComposeObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .sources(sources)
                    .headers(headers)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("合并 MinIO 分片失败", ex);
        }
    }

    @Override
    public List<String> listObjectKeys(String objectPrefix) {
        List<String> objectKeys = new ArrayList<String>();
        Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(minioProperties.getBucketName())
                .prefix(objectPrefix)
                .recursive(true)
                .build());
        for (Result<Item> result : results) {
            try {
                objectKeys.add(result.get().objectName());
            } catch (Exception ex) {
                throw new IllegalStateException("读取 MinIO 对象列表失败", ex);
            }
        }
        return objectKeys;
    }

    @Override
    public void deleteQuietly(String objectKey) {
        if (objectKey == null || objectKey.trim().isEmpty()) {
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            log.warn("delete minio object failed, objectKey={}", objectKey, ex);
        }
    }

    @Override
    public void deleteQuietly(List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        for (String objectKey : objectKeys) {
            deleteQuietly(objectKey);
        }
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.trim().isEmpty()) {
            return DEFAULT_CONTENT_TYPE;
        }
        return contentType.trim();
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "download";
        }
        return fileName.replace("\"", "");
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }
}
