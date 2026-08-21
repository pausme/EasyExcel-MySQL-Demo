package com.huang.demo.common.lock;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisDistributedLockServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void tryLockAcquiresRedisLockWithTtl() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        DistributedLockService lockService = new RedisDistributedLockService(stringRedisTemplate);

        assertTrue(lockService.tryLock(" demo:lock ", " owner-1 ", Duration.ofSeconds(10)));
        verify(valueOperations).setIfAbsent("demo:lock", "owner-1", Duration.ofSeconds(10));
    }

    @Test
    void tryLockReturnsFalseWhenRedisRejectsLock() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        DistributedLockService lockService = new RedisDistributedLockService(stringRedisTemplate);

        assertFalse(lockService.tryLock("demo:lock", "owner-1", Duration.ofSeconds(10)));
    }

    @Test
    void releaseOnlyDeletesLockOwnedByToken() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        DistributedLockService lockService = new RedisDistributedLockService(stringRedisTemplate);

        assertTrue(lockService.release(" demo:lock ", " owner-1 "));
        verify(stringRedisTemplate).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void invalidLockArgumentsReturnFalse() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        DistributedLockService lockService = new RedisDistributedLockService(stringRedisTemplate);

        assertFalse(lockService.tryLock(" ", "owner-1", Duration.ofSeconds(10)));
        assertFalse(lockService.release("demo:lock", " "));
    }
}
