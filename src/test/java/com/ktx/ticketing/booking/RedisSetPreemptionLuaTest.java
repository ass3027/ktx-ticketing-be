package com.ktx.ticketing.booking;

import com.ktx.ticketing.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선점 Lua 스크립트(SREM/SPOP + 선점시각 HSET 원자화)를 실제 Redis 로 검증한다(T3-10 갈래 1).
 * 검증 대상 계약: <b>실제 선점에 성공한 좌석에만 ts 마커가 찍힌다</b> — reconcile 가 in-flight 좌석을
 * 식별해 오버셀을 막는 토대. 미존재 좌석 선점 실패는 마커를 남기지 않아야 한다.
 */
class RedisSetPreemptionLuaTest extends AbstractIntegrationTest {

    private static final long SID = 999_001L;
    private static final String AVAIL_KEY = "avail:" + SID;
    private static final String TS_KEY = "preempt:ts:" + SID;

    @Autowired
    private RedisSetPreemption preemption;
    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void cleanup() {
        redis.delete(AVAIL_KEY);
        redis.delete(TS_KEY);
    }

    @Test
    void tryPreemptSeat_선점_성공시_좌석을_제거하고_선점시각을_기록() {
        preemption.initInventory(SID, List.of(10L, 20L));
        long before = System.currentTimeMillis();

        boolean won = preemption.tryPreemptSeat(SID, 10L);

        assertThat(won).isTrue();
        assertThat(redis.opsForSet().isMember(AVAIL_KEY, "10")).isFalse(); // 선점으로 풀에서 빠짐
        Object ts = redis.opsForHash().get(TS_KEY, "10");
        assertThat(ts).as("선점 승자에게 ts 마커가 찍혀야 한다").isNotNull();
        assertThat(Long.parseLong(ts.toString())).isGreaterThanOrEqualTo(before);
    }

    @Test
    void tryPreemptSeat_미존재_좌석_선점_실패시_선점시각_미기록() {
        preemption.initInventory(SID, List.of(10L));

        boolean won = preemption.tryPreemptSeat(SID, 99L); // Set 에 없는 좌석 = 선점 실패

        assertThat(won).isFalse();
        // SREM 이 0 을 반환했으므로 HSET 이 실행되면 안 된다 — 패배/미존재는 ts 를 남기지 않는다
        assertThat(redis.opsForHash().hasKey(TS_KEY, "99")).isFalse();
    }

    @Test
    void popAnySeat_뽑힌_좌석에_선점시각을_기록() {
        preemption.initInventory(SID, List.of(10L));

        Long seat = preemption.popAnySeat(SID);

        assertThat(seat).isEqualTo(10L);
        assertThat(redis.opsForSet().isMember(AVAIL_KEY, "10")).isFalse(); // SPOP = 풀에서 제거
        assertThat(redis.opsForHash().hasKey(TS_KEY, "10")).isTrue();
    }

    @Test
    void popAnySeat_빈_Set이면_null이고_마커도_없음() {
        preemption.initInventory(SID, List.of());

        assertThat(preemption.popAnySeat(SID)).isNull();
        assertThat(redis.hasKey(TS_KEY)).isFalse();
    }
}
