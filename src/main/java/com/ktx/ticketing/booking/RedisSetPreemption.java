package com.ktx.ticketing.booking;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redis Set avail:{scheduleId}를 원자적으로 조작해 좌석을 선점하는 {@link SeatPreemption} 구현체.
 * SREM (SEAT모드): 특정 seatInventoryId 1개를 Set에서 제거 — 제거 성공 = 선점 승자
 * SPOP (AUTO모드): Set에서 임의의 원소 1개를 꺼냄 — 반환값 있음 = 선점 승자
 * 취소/만료 시 SADD로 반환.
 *
 * <p><b>선점 시각 마커(T3-10 reconcile 정합성):</b> 모든 선점(SREM/SPOP)은 좌석별 "마지막 선점 시각"을
 * {@code preempt:ts:{scheduleId}} 해시에 <b>선점과 한 Lua 스크립트로 원자 기록</b>한다. reconcile 잡이
 * "DB는 AVAILABLE인데 Redis Set엔 부재"인 missing 좌석을 가용 풀로 되돌릴(SADD) 때, 이 마커가 최근이면
 * {@code [SREM~커밋]} in-flight 윈도우로 보고 건너뛰어 <b>오버셀</b>을 막는다. (설계: {@code docs/KTX_Ticketing_Reconcile_Design.md} §7)
 * HSET은 실제 선점(SREM/SPOP 성공) 시에만 찍으므로 선점 패자·미존재 좌석은 마커를 갱신하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RedisSetPreemption implements SeatPreemption {

    static final String KEY_PREFIX = "avail:";
    static final String TS_KEY_PREFIX = "preempt:ts:";

    /**
     * SEAT 선점: SREM 으로 좌석 제거에 성공(=선점 승자)한 경우에만 ts 마커를 찍고 1을 반환.
     * KEYS[1]=avail:{sid} KEYS[2]=preempt:ts:{sid} / ARGV[1]=seatId ARGV[2]=nowMillis
     */
    private static final RedisScript<Long> PREEMPT_SEAT = RedisScript.of("""
            if redis.call('SREM', KEYS[1], ARGV[1]) == 1 then
                redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
                return 1
            end
            return 0
            """, Long.class);

    /**
     * AUTO 선점: SPOP 으로 뽑은 좌석에 ts 마커를 찍고 그 seatId 를 반환(빈 Set 이면 false→nil).
     * KEYS[1]=avail:{sid} KEYS[2]=preempt:ts:{sid} / ARGV[1]=nowMillis
     */
    private static final RedisScript<String> POP_ANY_SEAT = RedisScript.of("""
            local seat = redis.call('SPOP', KEYS[1])
            if seat then
                redis.call('HSET', KEYS[2], seat, ARGV[1])
                return seat
            end
            return false
            """, String.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    @Override
    public boolean tryPreemptSeat(Long scheduleId, Long seatInventoryId) {
        Long won = redis.execute(PREEMPT_SEAT,
                List.of(key(scheduleId), tsKey(scheduleId)),
                seatInventoryId.toString(), nowMillis());
        return won != null && won == 1L;
    }

    @Override
    public @Nullable Long popAnySeat(Long scheduleId) {
        String seat = redis.execute(POP_ANY_SEAT,
                List.of(key(scheduleId), tsKey(scheduleId)),
                nowMillis());
        return seat == null ? null : Long.parseLong(seat);
    }

    @Override
    public void returnSeat(Long scheduleId, Long seatInventoryId) {
        redis.opsForSet().add(key(scheduleId), seatInventoryId.toString());
    }

    @Override
    public void initInventory(Long scheduleId, Iterable<Long> seatInventoryIds) {
        String key = key(scheduleId);
        redis.delete(key);
        redis.delete(tsKey(scheduleId)); // 선점 시각 마커도 함께 초기화(스케줄 재초기화 = 깨끗한 상태)
        for (Long id : seatInventoryIds) {
            redis.opsForSet().add(key, id.toString());
        }
    }

    @Override
    public long availableCount(Long scheduleId) {
        Long size = redis.opsForSet().size(key(scheduleId));
        return size == null ? 0 : size;
    }

    @Override
    public Set<Long> availableSeatIds(Long scheduleId) {
        Set<String> members = redis.opsForSet().members(key(scheduleId));
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        return members.stream().map(Long::parseLong).collect(Collectors.toSet());
    }

    @Override
    public void removeSeat(Long scheduleId, Long seatInventoryId) {
        redis.opsForSet().remove(key(scheduleId), seatInventoryId.toString());
    }

    @Override
    public long preemptedAtMillis(Long scheduleId, Long seatInventoryId) {
        Object ts = redis.opsForHash().get(tsKey(scheduleId), seatInventoryId.toString());
        return ts == null ? 0L : Long.parseLong(ts.toString());
    }

    private String nowMillis() {
        return Long.toString(clock.millis());
    }

    private String key(Long scheduleId) {
        return KEY_PREFIX + scheduleId;
    }

    private String tsKey(Long scheduleId) {
        return TS_KEY_PREFIX + scheduleId;
    }
}
