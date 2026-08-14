package com.huang.demo.task.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.task")
@Getter
@Setter
public class TaskCenterProperties {

    private boolean initEnabled = true;

    private String redisKeyPrefix = "task:center:";

    private int cacheRetentionHours = 24;

    private int maxPageSize = 100;

    private String defaultOwnerId = "anonymous";

    private int maxRetryCount = 3;

    private boolean recoveryEnabled = true;

    private String workerId;

    private int recoveryHeartbeatTimeoutSeconds = 120;

    private int recoveryBatchSize = 20;
}
