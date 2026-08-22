package com.huang.demo.common.compensation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.compensation")
public class CompensationProperties {

    private boolean autoExecuteEnabled = true;

    private long autoExecuteInitialDelayMillis = 60000L;

    private long autoExecuteFixedDelayMillis = 60000L;

    private int autoExecuteBatchSize = 20;

    private String autoExecuteLockKey = "compensation:auto-execute:lock";

    private long autoExecuteLockTtlSeconds = 300L;

    private int retryBackoffBaseSeconds = 60;

    private int retryBackoffMaxSeconds = 3600;
}
