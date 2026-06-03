package com.ktx.ticketing.booking;

import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis Set avail:{scheduleId}를 원자적으로 조작해 좌석을 선점한다.
 * SREM (SEAT모드): 특정 seatInventoryId 1개를 Set에서 제거 — 제거 성공 = 선점 승자
 * SPOP (AUTO모드): Set에서 임의의 원소 1개를 꺼냄 — 반환값 있음 = 선점 승자
 * 취소/만료 시 SADD로 반환.
 */
@Service
public class SeatPreemptionService {

    static final String KEY_PREFIX = "avail:";

    private final StringRedisTemplate redis;

    public SeatPreemptionService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** SEAT 모드: 지정 seatInventoryId 선점 시도. true = 승자 */
    public boolean tryPreemptSeat(Long scheduleId, Long seatInventoryId) {
        Long removed = redis.opsForSet().remove(key(scheduleId), seatInventoryId.toString());
        return removed != null && removed > 0;
    }

    /** AUTO 모드: 임의 좌석 선점. 반환값 없으면 잔여석 없음 */
    public @Nullable Long popAnySeat(Long scheduleId) {
        String value = redis.opsForSet().pop(key(scheduleId));
        return value == null ? null : Long.parseLong(value);
    }

    /** 취소/만료 시 좌석 반환 */
    public void returnSeat(Long scheduleId, Long seatInventoryId) {
        redis.opsForSet().add(key(scheduleId), seatInventoryId.toString());
    }

    /** 스케줄의 전체 가용 좌석 Set 적재 (seed 후 초기화용) */
    public void initAvailSet(Long scheduleId, Iterable<Long> seatInventoryIds) {
        String key = key(scheduleId);
        redis.delete(key);
        for (Long id : seatInventoryIds) {
            redis.opsForSet().add(key, id.toString());
        }
    }

    public long availableCount(Long scheduleId) {
        Long size = redis.opsForSet().size(key(scheduleId));
        return size == null ? 0 : size;
    }

    private String key(Long scheduleId) {
        return KEY_PREFIX + scheduleId;
    }
}
