package com.huang.demo.common.resilience;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RateLimitResult {

    private final boolean allowed;

    private final long retryAfterSeconds;

    public static RateLimitResult allowed() {
        return RateLimitResult.builder()
                .allowed(true)
                .retryAfterSeconds(0L)
                .build();
    }

    public static RateLimitResult rejected(long retryAfterSeconds) {
        return RateLimitResult.builder()
                .allowed(false)
                .retryAfterSeconds(retryAfterSeconds)
                .build();
    }
}
