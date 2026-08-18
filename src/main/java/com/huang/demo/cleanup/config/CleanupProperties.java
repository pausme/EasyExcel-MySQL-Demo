package com.huang.demo.cleanup.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.cleanup")
@Getter
@Setter
public class CleanupProperties {

    private boolean enabled = true;

    private long initialDelayMillis = 300000L;

    private long fixedDelayMillis = 3600000L;

    private int batchSize = 200;

    private int taskRetentionHours = 168;

    private int uploadTaskRetentionHours = 24;

    private int deletedFileRetentionHours = 24;

    private int importStageRetentionHours = 24;
}
