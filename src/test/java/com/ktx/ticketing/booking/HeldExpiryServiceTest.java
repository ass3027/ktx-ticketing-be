package com.ktx.ticketing.booking;

import com.ktx.ticketing.admission.AdmissionService;
import com.ktx.ticketing.booking.ReservationLifecycleTransactionHelper.ExpiredRelease;
import com.ktx.ticketing.domain.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * HeldExpiryService(sweep 오케스트레이터) 검증 — 핵심은 "만료 건마다 커밋 후 좌석 반환(SADD)+활성 슬롯
 * 반환(DECR)을, <b>실제 만료된 경우에만</b> 수행한다". DB 전이는 헬퍼 책임이라 mock 으로 대체한다.
 */
@ExtendWith(MockitoExtension.class)
class HeldExpiryServiceTest {

    // 서로 다른 값으로 둬서 scheduleId/seatId 인자 전치 버그를 잡는다.
    private static final long SCHEDULE_A = 1L, SEAT_A = 42L;
    private static final long SCHEDULE_B = 2L, SEAT_B = 43L;
    private static final int BATCH = 100;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-01T08:00:00Z"), ZoneOffset.UTC);

    @Mock ReservationRepository reservationRepository;
    @Mock ReservationLifecycleTransactionHelper txHelper;
    @Mock SeatPreemption preemption;
    @Mock AdmissionService admissionService;

    HeldExpiryService service;

    @BeforeEach
    void setUp() {
        service = new HeldExpiryService(reservationRepository, txHelper, preemption,
                admissionService, new ExpiryProperties(BATCH), CLOCK);
    }

    @Test
    void sweep_만료건마다_좌석반환_SADD_그리고_활성슬롯_DECR() {
        // 주입 Clock 의 now 로 조회되는지(시간 결정성)도 함께 확인
        when(reservationRepository.findExpiredHeldIds(eq(LocalDateTime.now(CLOCK)), any()))
                .thenReturn(List.of(10L, 20L));
        when(txHelper.expire(10L)).thenReturn(new ExpiredRelease(SCHEDULE_A, SEAT_A));
        when(txHelper.expire(20L)).thenReturn(new ExpiredRelease(SCHEDULE_B, SEAT_B));

        int expired = service.sweep();

        assertThat(expired).isEqualTo(2);
        verify(preemption).returnSeat(SCHEDULE_A, SEAT_A);
        verify(admissionService).leave(SCHEDULE_A);
        verify(preemption).returnSeat(SCHEDULE_B, SEAT_B);
        verify(admissionService).leave(SCHEDULE_B);
    }

    @Test
    void sweep_경합으로_이미_처리된건은_건너뜀_부수효과_없음() {
        when(reservationRepository.findExpiredHeldIds(any(), any())).thenReturn(List.of(10L));
        when(txHelper.expire(10L)).thenReturn(null); // 사용자가 이미 확정/취소 → 헬퍼가 no-op

        int expired = service.sweep();

        assertThat(expired).isZero();
        verify(preemption, never()).returnSeat(anyLong(), anyLong()); // 이중 SADD = 오버셀 방지
        verify(admissionService, never()).leave(anyLong());           // 이중 DECR = 카운터 훼손 방지
    }

    @Test
    void sweep_만료대상_없으면_아무것도_안함() {
        when(reservationRepository.findExpiredHeldIds(any(), any())).thenReturn(List.of());

        assertThat(service.sweep()).isZero();
        verifyNoInteractions(txHelper, preemption, admissionService);
    }
}
