package com.ktx.ticketing.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeatPreemptionServiceTest {

    @Mock private StringRedisTemplate redis;
    @Mock private SetOperations<String, String> setOps;
    @InjectMocks private SeatPreemptionService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForSet()).thenReturn(setOps);
    }

    @Test
    void tryPreemptSeat_SREM이_1_반환하면_선점_성공() {
        when(setOps.remove("avail:1", "42")).thenReturn(1L);

        boolean result = service.tryPreemptSeat(1L, 42L);

        assertThat(result).isTrue();
    }

    @Test
    void tryPreemptSeat_SREM이_0_반환하면_선점_실패() {
        when(setOps.remove("avail:1", "42")).thenReturn(0L);

        boolean result = service.tryPreemptSeat(1L, 42L);

        assertThat(result).isFalse();
    }

    @Test
    void tryPreemptSeat_SREM이_null_반환해도_NPE없이_선점_실패() {
        // 파이프라인/트랜잭션 모드에서 Redis 반환이 null일 수 있음 → 널 가드가 false로 흡수
        when(setOps.remove("avail:1", "42")).thenReturn(null);

        boolean result = service.tryPreemptSeat(1L, 42L);

        assertThat(result).isFalse();
    }

    @Test
    void popAnySeat_값이_있으면_seatInventoryId_반환() {
        when(setOps.pop("avail:1")).thenReturn("99");

        Long result = service.popAnySeat(1L);

        assertThat(result).isEqualTo(99L);
    }

    @Test
    void popAnySeat_Set이_비어있으면_null_반환() {
        when(setOps.pop("avail:1")).thenReturn(null);

        Long result = service.popAnySeat(1L);

        assertThat(result).isNull();
    }

    @Test
    void returnSeat_avail키에_seatInventoryId를_SADD() {
        // 위임 메서드지만 키 포맷(avail:{id})과 Long→String 변환 계약을 고정한다
        service.returnSeat(1L, 42L);

        verify(setOps).add("avail:1", "42");
    }

    @Test
    void initAvailSet_기존_Set을_먼저_비우고_전체_좌석을_적재() {
        service.initAvailSet(1L, List.of(10L, 20L));

        // delete가 add보다 먼저여야 재초기화 시 좌석 중복 적재를 막는다 → 순서가 계약
        var inOrder = inOrder(redis, setOps);
        inOrder.verify(redis).delete("avail:1");
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
