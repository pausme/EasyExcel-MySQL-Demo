package com.huang.demo.common.resilience;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.resilience.rate-limit")
@Getter
@Setter
public class RateLimitProperties {

    private boolean enabled = true;

    private int windowSeconds = 60;

    private int userLimit = 30;

    private int globalLimit = 300;

    private int cleanupMaxKeys = 1000;
}
