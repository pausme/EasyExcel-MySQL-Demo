package com.huang.demo.excel.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.minio")
@Getter
@Setter
public class MinioProperties {

    private String endpoint;

    private String accessKey;

    private String secretKey;

    private String bucketName;

    private String exportObjectPrefix = "excel/student";

    private int downloadUrlExpireMinutes = 30;
}
