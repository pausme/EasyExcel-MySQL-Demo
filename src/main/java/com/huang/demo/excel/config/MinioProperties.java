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

    private String publicEndpoint;

    private String accessKey;

    private String secretKey;

    private String bucketName;

    private String exportObjectPrefix = "excel/student";

    private String importSourceObjectPrefix = "excel/student/import-source";

    private String importErrorObjectPrefix = "excel/student/import-error";

    private int downloadUrlExpireMinutes = 30;

    private boolean lifecycleEnabled = true;

    private int lifecycleExpireDays = 1;

    private int importSourceRetentionDays = 1;
}
