package com.ktx.ticketing.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 위임 메서드(returnSeat/initInventory/availableCount)의 키 포맷·널 가드·연산 순서 계약을 고정한다.
 * 선점 경로(tryPreemptSeat/popAnySeat)는 Lua 스크립트라 모킹 충실도가 낮아 실제 Redis 를 쓰는
 * {@code RedisSetPreemptionLuaTest}(Testcontainers)에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RedisSetPreemptionTest {

    @Mock private StringRedisTemplate redis;
    @Mock private SetOperations<String, String> setOps;
    @Mock private Clock clock;
    @InjectMocks private RedisSetPreemption service;

    @BeforeEach
    void setUp() {
        when(redis.opsForSet()).thenReturn(setOps);
    }

    @Test
    void returnSeat_avail키에_seatInventoryId를_SADD() {
        // 위임 메서드지만 키 포맷(avail:{id})과 Long→String 변환 계약을 고정한다
        service.returnSeat(1L, 42L);

        verify(setOps).add("avail:1", "42");
    }

    @Test
    void initInventory_avail와_선점시각마커를_먼저_비우고_전체_좌석을_적재() {
        service.initInventory(1L, List.of(10L, 20L));

        // delete가 add보다 먼저여야 재초기화 시 좌석 중복 적재를 막는다 → 순서가 계약.
        // 선점 시각 마커(preempt:ts:{id})도 함께 비워야 이전 스케줄의 묵은 ts가 reconcile를 오판시키지 않는다.
        var inOrder = inOrder(redis, setOps);
        inOrder.verify(redis).delete("avail:1");
        inOrder.verify(redis).delete("preempt:ts:1");
        inOrder.verify(setOps).add("avail:1", "10");
        inOrder.verify(setOps).add("avail:1", "20");
    }

    @Test
    void availableCount_size를_그대로_반환() {
        when(setOps.size("avail:1")).thenReturn(7L);

        assertThat(service.availableCount(1L)).isEqualTo(7L);
    }

    @Test
    void availableCount_size가_null이면_0_반환() {
        when(setOps.size("avail:1")).thenReturn(null);

        assertThat(service.availableCount(1L)).isZero();
    }
}
