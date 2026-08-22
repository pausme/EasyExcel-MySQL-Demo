package com.huang.demo.common.resilience;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitServiceTest {

    @Test
    void rejectsWhenUserLimitExceededInSameWindow() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setWindowSeconds(60);
        properties.setUserLimit(1);
        properties.setGlobalLimit(100);
        RateLimitService service = new RateLimitService(properties);

        assertTrue(service.tryAcquire("excel-export-submit", "user-1").isAllowed());
        RateLimitResult rejected = service.tryAcquire("excel-export-submit", "user-1");

        assertFalse(rejected.isAllowed());
        assertTrue(rejected.getRetryAfterSeconds() > 0L);
    }

    @Test
    void disabledLimiterAlwaysAllows() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(false);
        properties.setUserLimit(0);
        properties.setGlobalLimit(0);
        RateLimitService service = new RateLimitService(properties);

        assertTrue(service.tryAcquire("file-download-sign", "user-1").isAllowed());
        assertTrue(service.tryAcquire("file-download-sign", "user-1").isAllowed());
    }
}
