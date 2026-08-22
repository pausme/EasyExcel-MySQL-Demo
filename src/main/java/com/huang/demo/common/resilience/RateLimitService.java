package com.huang.demo.common.resilience;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RateLimitService {

    private final RateLimitProperties properties;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<String, WindowCounter>();

    public RateLimitService(RateLimitProperties properties) {
        this.properties = properties;
    }

    public RateLimitResult tryAcquire(String scope, String identity) {
        if (!properties.isEnabled()) {
            return RateLimitResult.allowed();
        }
        String normalizedScope = normalizeScope(scope);
        int windowSeconds = Math.max(1, properties.getWindowSeconds());
        long windowStart = Instant.now().getEpochSecond() / windowSeconds * windowSeconds;
        RateLimitResult userResult = incrementAndCheck(
                "user:" + normalizedScope + ":" + normalizeIdentity(identity), windowStart, properties.getUserLimit());
        if (!userResult.isAllowed()) {
            return userResult;
        }
        return incrementAndCheck("global:" + normalizedScope, windowStart, properties.getGlobalLimit());
    }

    private RateLimitResult incrementAndCheck(String key, long windowStart, int limit) {
        if (limit <= 0) {
            return RateLimitResult.allowed();
        }
        WindowCounter counter = counters.compute(key, (currentKey, currentValue) -> {
            if (currentValue == null || currentValue.getWindowStart() != windowStart) {
                return new WindowCounter(windowStart);
            }
            return currentValue;
        });
        int current = counter.incrementAndGet();
        cleanupIfNecessary(windowStart);
        if (current > limit) {
            long retryAfterSeconds = Math.max(1L,
                    windowStart + Math.max(1, properties.getWindowSeconds()) - Instant.now().getEpochSecond());
            return RateLimitResult.rejected(retryAfterSeconds);
        }
        return RateLimitResult.allowed();
    }

    private void cleanupIfNecessary(long currentWindowStart) {
        int cleanupMaxKeys = Math.max(100, properties.getCleanupMaxKeys());
        if (counters.size() <= cleanupMaxKeys) {
            return;
        }
        Iterator<Map.Entry<String, WindowCounter>> iterator = counters.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, WindowCounter> entry = iterator.next();
            if (entry.getValue().getWindowStart() < currentWindowStart) {
                iterator.remove();
            }
        }
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.trim().isEmpty()) {
            return "default";
        }
        return scope.trim();
    }

    private String normalizeIdentity(String identity) {
        if (identity == null || identity.trim().isEmpty()) {
            return "anonymous";
        }
        String normalized = identity.trim();
        return normalized.length() > 128 ? normalized.substring(0, 128) : normalized;
    }

    private static class WindowCounter {

        private final long windowStart;
        private final AtomicInteger value = new AtomicInteger();

        WindowCounter(long windowStart) {
            this.windowStart = windowStart;
        }

        long getWindowStart() {
            return windowStart;
        }

        int incrementAndGet() {
            return value.incrementAndGet();
        }
    }
}
