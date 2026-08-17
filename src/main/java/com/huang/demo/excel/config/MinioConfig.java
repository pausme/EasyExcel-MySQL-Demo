package com.huang.demo.excel.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

@Configuration
public class MinioConfig {

    @Bean
    @Primary
    public MinioClient minioClient(MinioProperties properties) {
        if (!StringUtils.hasText(properties.getEndpoint())
                || !StringUtils.hasText(properties.getAccessKey())
                || !StringUtils.hasText(properties.getSecretKey())
                || !StringUtils.hasText(properties.getBucketName())) {
            throw new IllegalStateException("MinIO 配置不完整，请设置 MINIO_ENDPOINT、MINIO_ACCESS_KEY、MINIO_SECRET_KEY 和 MINIO_BUCKET_NAME");
        }
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    @Bean
    @Qualifier("minioPublicClient")
    public MinioClient minioPublicClient(MinioProperties properties) {
        String publicEndpoint = StringUtils.hasText(properties.getPublicEndpoint())
                ? properties.getPublicEndpoint()
                : properties.getEndpoint();
        return MinioClient.builder()
                .endpoint(publicEndpoint)
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}
