package com.ktx.ticketing.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;

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
        // setOps 모킹은 Set 경로 테스트(returnSeat/initInventory/availableCount/returnSeats)에서만 쓰여
        // strict stub 정책상 Hash 경로 테스트(preemptedAtMillisAll)에선 UnnecessaryStubbingException 이 난다.
        // lenient 로 풀어 공통 BeforeEach 의 비용 의도를 유지한다.
        lenient().when(redis.opsForSet()).thenReturn(setOps);
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

    @Test
    void returnSeats_avail키에_여러_seatInventoryId를_한_번에_SADD() {
        // 벌크 경로(T4-3 워밍업 가속): 좌석마다 SADD 호출 N회 → 가변인자 1회로 축약.
        // setOps.add(key, String...) 한 번만 호출돼야 한다.
        service.returnSeats(1L, List.of(10L, 20L, 30L));

        verify(setOps).add("avail:1", "10", "20", "30");
        verifyNoMoreInteractions(setOps);
    }

    @Test
    void returnSeats_빈_컬렉션이면_Redis_호출_없음() {
        // 가변인자 SADD 는 빈 배열을 못 받는다(IllegalArgumentException) — 호출 전에 가드.
        service.returnSeats(1L, List.of());

        verify(setOps, never()).add(anyString(), any(String[].class));
    }

    @Test
    void preemptedAtMillisAll_HGETALL_결과를_Long맵으로_변환() {
        // 좌석별 HGET N회 → HGETALL 1회로 축약. 키·값 둘 다 String 으로 저장돼 있어 Long 변환이 계약.
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries("preempt:ts:1")).thenReturn(Map.of("10", "1700000000000", "20", "1700000000500"));

        Map<Long, Long> result = service.preemptedAtMillisAll(1L);

        assertThat(result).containsOnly(
                Map.entry(10L, 1_700_000_000_000L),
                Map.entry(20L, 1_700_000_000_500L));
    }

    @Test
    void preemptedAtMillisAll_빈_해시면_빈_맵_반환() {
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries("preempt:ts:1")).thenReturn(Map.of());

        assertThat(service.preemptedAtMillisAll(1L)).isEmpty();
    }
}
