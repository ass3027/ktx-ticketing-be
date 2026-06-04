package com.ktx.ticketing.admission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdmissionService 단위 테스트 — 핵심은 "INCR 반환값으로 상한 K 를 판정하고, 초과 시 DECR 롤백 후 거절"하는
 * 동시성 안전 로직이다. 활성 카운터 키 조작과 토큰 발급/미발급을 검증한다.
 * (실제 Redis 원자성·동시 호출은 통합 테스트 T3-11 의 책임)
 */
@ExtendWith(MockitoExtension.class)
class AdmissionServiceTest {

    private static final long SCHEDULE_ID = 1L;
    private static final long USER_ID = 7L;
    private static final int MAX_ACTIVE = 100;
    private static final String ACTIVE_KEY = "active:" + SCHEDULE_ID;

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @Mock EntryTokenStore tokenStore;

    AdmissionService service;

    @BeforeEach
    void setUp() {
        AdmissionProperties props = new AdmissionProperties(
                MAX_ACTIVE, Duration.ofMinutes(10), Duration.ofSeconds(5));
        service = new AdmissionService(redis, tokenStore, props);
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void 활성자가_K_미만이면_토큰_발급하고_롤백하지_않음() {
        when(valueOps.increment(ACTIVE_KEY)).thenReturn(50L); // K 미만
        EntryToken issued = new EntryToken("tok-1");
        when(tokenStore.issue(eq(SCHEDULE_ID), eq(USER_ID), any())).thenReturn(issued);

        AdmissionResult result = service.tryEnter(SCHEDULE_ID, USER_ID);

        assertThat(result).isInstanceOf(AdmissionResult.Admitted.class);
        assertThat(((AdmissionResult.Admitted) result).token()).isEqualTo(issued);
        verify(valueOps, never()).decrement(ACTIVE_KEY);
    }

    @Test
    void 활성자가_정확히_K면_아직_허용() {
        when(valueOps.increment(ACTIVE_KEY)).thenReturn((long) MAX_ACTIVE); // == K → 허용(초과 아님)
        when(tokenStore.issue(eq(SCHEDULE_ID), eq(USER_ID), any())).thenReturn(new EntryToken("tok-2"));

        AdmissionResult result = service.tryEnter(SCHEDULE_ID, USER_ID);

        assertThat(result).isInstanceOf(AdmissionResult.Admitted.class);
        verify(valueOps, never()).decrement(ACTIVE_KEY);
    }

    @Test
    void 활성자가_K_초과면_DECR_롤백하고_거절_토큰_미발급() {
        when(valueOps.increment(ACTIVE_KEY)).thenReturn(MAX_ACTIVE + 1L); // K 초과

        AdmissionResult result = service.tryEnter(SCHEDULE_ID, USER_ID);

        assertThat(result).isInstanceOf(AdmissionResult.Rejected.class);
        assertThat(((AdmissionResult.Rejected) result).retryAfter()).isEqualTo(Duration.ofSeconds(5));
        verify(valueOps).decrement(ACTIVE_KEY); // 점유 실패 → 즉시 롤백
        verify(tokenStore, never()).issue(any(), any(), any());
    }

    @Test
    void leave는_활성자_카운터를_감소() {
        service.leave(SCHEDULE_ID);

        verify(valueOps).decrement(ACTIVE_KEY);
    }
}
