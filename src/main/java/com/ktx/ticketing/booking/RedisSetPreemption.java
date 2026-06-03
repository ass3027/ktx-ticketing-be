package com.ktx.ticketing.booking;

import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis Set avail:{scheduleId}를 원자적으로 조작해 좌석을 선점하는 {@link SeatPreemption} 구현체.
 * SREM (SEAT모드): 특정 seatInventoryId 1개를 Set에서 제거 — 제거 성공 = 선점 승자
 * SPOP (AUTO모드): Set에서 임의의 원소 1개를 꺼냄 — 반환값 있음 = 선점 승자
 * 취소/만료 시 SADD로 반환.
 */
@Service
public class RedisSetPreemption implements SeatPreemption {

    static final String KEY_PREFIX = "avail:";

    private final StringRedisTemplate redis;

    public RedisSetPreemption(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryPreemptSeat(Long scheduleId, Long seatInventoryId) {
        Long removed = redis.opsForSet().remove(key(scheduleId), seatInventoryId.toString());
        return removed != null && removed > 0;
    }

    @Override
    public @Nullable Long popAnySeat(Long scheduleId) {
        String value = redis.opsForSet().pop(key(scheduleId));
        return value == null ? null : Long.parseLong(value);
    }

    @Override
    public void returnSeat(Long scheduleId, Long seatInventoryId) {
        redis.opsForSet().add(key(scheduleId), seatInventoryId.toString());
    }

    @Override
    public void initInventory(Long scheduleId, Iterable<Long> seatInventoryIds) {
        String key = key(scheduleId);
        redis.delete(key);
        for (Long id : seatInventoryIds) {
            redis.opsForSet().add(key, id.toString());
        }
    }

    @Override
    public long availableCount(Long scheduleId) {
        Long size = redis.opsForSet().size(key(scheduleId));
        return size == null ? 0 : size;
    }

    private String key(Long scheduleId) {
        return KEY_PREFIX + scheduleId;
    }
}
