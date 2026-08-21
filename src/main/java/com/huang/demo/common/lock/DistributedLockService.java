package com.huang.demo.common.lock;

import java.time.Duration;

public interface DistributedLockService {

    boolean tryLock(String lockKey, String ownerToken, Duration ttl);

    boolean release(String lockKey, String ownerToken);
}
