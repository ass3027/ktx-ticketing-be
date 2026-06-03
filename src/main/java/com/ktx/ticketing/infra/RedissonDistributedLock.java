package com.ktx.ticketing.infra;

import org.jspecify.annotations.Nullable;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redisson 분산락 기반 {@link DistributedLock} 구현체.
 * 락이 작업(트랜잭션 등)을 감싸는 구조이며, finally 에서 현재 스레드 보유 시에만 해제해 커밋 후 해제를 보장한다.
 *
 * <p>대기/리스 시간은 락 라이브러리별 튜닝 관심사이므로 구현체에 둔다(인터페이스 계약에서 분리).
 */
@Component
public class RedissonDistributedLock implements DistributedLock {

    static final String LOCK_PREFIX = "lock:";
    static final long WAIT_SECONDS = 5;
    static final long LEASE_SECONDS = 10;

    private final RedissonClient redisson;

    public RedissonDistributedLock(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public <T extends @Nullable Object> T executeWithLock(String key, Supplier<T> action) {
        RLock lock = redisson.getLock(LOCK_PREFIX + key);
        try {
            if (!lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS)) {
                return null;
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
