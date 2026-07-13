package com.ktx.ticketing.booking;

import com.ktx.ticketing.booking.ReservationLifecycleTransactionHelper.ExpiredRelease;
import com.ktx.ticketing.domain.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * HeldExpiryService(sweep 오케스트레이터) 검증 — 핵심은 정합성 규칙: 커밋 후 부수효과 대상으로
 * <b>실제 만료된 건(expire 가 non-null)만</b> scheduleId별로 모아 전략({@link ExpirySideEffects}) 에 넘긴다.
 * 발사 방식(배치/건별)은 전략 구현체 테스트에서 검증하므로 여기선 mock 으로 대체해 "무엇을 넘기는지"만 본다.
 */
@ExtendWith(MockitoExtension.class)
class HeldExpiryServiceTest {

    // 서로 다른 값으로 둬서 scheduleId/seatId 인자 전치 버그를 잡는다.
    private static final long SCHEDULE_A = 1L, SEAT_A = 42L;
    private static final long SCHEDULE_B = 2L, SEAT_B = 43L, SEAT_B2 = 44L;
    private static final int BATCH = 100;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-01T08:00:00Z"), ZoneOffset.UTC);

    @Mock ReservationRepository reservationRepository;
    @Mock ReservationLifecycleTransactionHelper txHelper;
    @Mock ExpirySideEffects sideEffects;
    @Captor ArgumentCaptor<Map<Long, List<Long>>> seatsCaptor;

    HeldExpiryService service;

    @BeforeEach
    void setUp() {
        service = new HeldExpiryService(reservationRepository, txHelper, sideEffects,
                new ExpiryProperties(BATCH, true), CLOCK);
    }

    @Test
    void sweep_실제만료된건만_scheduleId별로_묶어_전략에_넘긴다() {
        // 주입 Clock 의 now 로 조회되는지(시간 결정성)도 함께 확인.
        // SCHEDULE_A 는 1건, SCHEDULE_B 는 2건 만료 → scheduleId별 좌석 묶음이 정확한지 본다.
        when(reservationRepository.findExpiredHeldIds(eq(LocalDateTime.now(CLOCK)), any()))
                .thenReturn(List.of(10L, 20L, 30L));
        when(txHelper.expire(10L)).thenReturn(new ExpiredRelease(SCHEDULE_A, SEAT_A));
        when(txHelper.expire(20L)).thenReturn(new ExpiredRelease(SCHEDULE_B, SEAT_B));
        when(txHelper.expire(30L)).thenReturn(new ExpiredRelease(SCHEDULE_B, SEAT_B2));

        int expired = service.sweep();

        assertThat(expired).isEqualTo(3);
        verify(sideEffects).release(seatsCaptor.capture());
        assertThat(seatsCaptor.getValue())
                .containsEntry(SCHEDULE_A, List.of(SEAT_A))
                .containsEntry(SCHEDULE_B, List.of(SEAT_B, SEAT_B2));
    }

    @Test
    void sweep_경합으로_이미_처리된건은_부수효과_대상에서_제외() {
        when(reservationRepository.findExpiredHeldIds(any(), any())).thenReturn(List.of(10L));
        when(txHelper.expire(10L)).thenReturn(null); // 사용자가 이미 확정/취소 → 헬퍼가 no-op

        int expired = service.sweep();

        assertThat(expired).isZero();
        // 만료 0건 → 빈 맵으로 전략 호출(이중 SADD/DECR = 오버셀·카운터 훼손 방지의 출발점).
        verify(sideEffects).release(Map.of());
    }

    @Test
    void sweep_만료대상_없으면_전이도_전략호출_좌석없이() {
        when(reservationRepository.findExpiredHeldIds(any(), any())).thenReturn(List.of());

        assertThat(service.sweep()).isZero();
        verifyNoInteractions(txHelper);        // 전이 자체가 없음
        verify(sideEffects).release(Map.of()); // 빈 맵 — 전략은 아무 부수효과도 발사하지 않음
    }
}
