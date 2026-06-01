package com.ktx.ticketing.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SeatPreemptionServiceTest {

    private StringRedisTemplate redis;
    private SetOperations<String, String> setOps;
    private SeatPreemptionService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        setOps = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(setOps);
        service = new SeatPreemptionService(redis);
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
    void returnSeat_SADD로_좌석_반환() {
        service.returnSeat(1L, 42L);

        verify(setOps).add("avail:1", "42");
    }
}
