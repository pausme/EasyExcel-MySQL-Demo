package com.huang.demo.common.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Collections;

@Service
public class RedisDistributedLockService implements DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLockService.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = createReleaseScript();
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30L);

    private final StringRedisTemplate stringRedisTemplate;

    public RedisDistributedLockService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(String lockKey, String ownerToken, Duration ttl) {
        String normalizedLockKey = normalizeLockKey(lockKey);
        String normalizedOwnerToken = normalizeOwnerToken(ownerToken);
        if (normalizedLockKey == null || normalizedOwnerToken == null) {
            return false;
        }
        try {
            Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(
                    normalizedLockKey, normalizedOwnerToken, normalizeTtl(ttl));
            return Boolean.TRUE.equals(locked);
        } catch (RuntimeException ex) {
            log.warn("acquire distributed lock failed, lockKey={}", normalizedLockKey, ex);
            return false;
        }
    }

    @Override
    public boolean release(String lockKey, String ownerToken) {
        String normalizedLockKey = normalizeLockKey(lockKey);
        String normalizedOwnerToken = normalizeOwnerToken(ownerToken);
        if (normalizedLockKey == null || normalizedOwnerToken == null) {
            return false;
        }
        try {
            Long released = stringRedisTemplate.execute(
                    RELEASE_SCRIPT, Collections.singletonList(normalizedLockKey), normalizedOwnerToken);
            return Long.valueOf(1L).equals(released);
        } catch (RuntimeException ex) {
            log.warn("release distributed lock failed, lockKey={}", normalizedLockKey, ex);
            return false;
        }
    }

    private static DefaultRedisScript<Long> createReleaseScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<Long>();
        script.setResultType(Long.class);
        script.setScriptText("if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end");
        return script;
    }

    private Duration normalizeTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return DEFAULT_TTL;
        }
        return ttl;
    }

    private String normalizeLockKey(String lockKey) {
        if (!StringUtils.hasText(lockKey)) {
            return null;
        }
        return lockKey.trim();
    }

    private String normalizeOwnerToken(String ownerToken) {
        if (!StringUtils.hasText(ownerToken)) {
            return null;
        }
        return ownerToken.trim();
    }
}
