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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    private static final long SCHEDULE_B = 2L, SEAT_B = 43L, SEAT_B2 = 44L;
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
    void sweep_만료건은_scheduleId별로_묶어_좌석반환_SADD_와_활성슬롯_DECRBY() {
        // 주입 Clock 의 now 로 조회되는지(시간 결정성)도 함께 확인.
        // SCHEDULE_A 는 1건, SCHEDULE_B 는 2건 만료 → scheduleId별 집계(건수·좌석묶음)가 정확한지 본다.
        when(reservationRepository.findExpiredHeldIds(eq(LocalDateTime.now(CLOCK)), any()))
                .thenReturn(List.of(10L, 20L, 30L));
        when(txHelper.expire(10L)).thenReturn(new ExpiredRelease(SCHEDULE_A, SEAT_A));
        when(txHelper.expire(20L)).thenReturn(new ExpiredRelease(SCHEDULE_B, SEAT_B));
        when(txHelper.expire(30L)).thenReturn(new ExpiredRelease(SCHEDULE_B, SEAT_B2));

        int expired = service.sweep();

        assertThat(expired).isEqualTo(3);
        // scheduleId별 1회 SADD 로 좌석 묶음을 반환 — 인자 전치·집계 누락 방지.
        verify(preemption).returnSeats(SCHEDULE_A, List.of(SEAT_A));
        verify(preemption).returnSeats(SCHEDULE_B, List.of(SEAT_B, SEAT_B2));
        // scheduleId별 만료 건수만큼 한 번에 DECRBY (A:1, B:2).
        verify(admissionService).leaveAll(Map.of(SCHEDULE_A, 1, SCHEDULE_B, 2));
    }

    @Test
    void sweep_경합으로_이미_처리된건은_건너뜀_부수효과_없음() {
        when(reservationRepository.findExpiredHeldIds(any(), any())).thenReturn(List.of(10L));
        when(txHelper.expire(10L)).thenReturn(null); // 사용자가 이미 확정/취소 → 헬퍼가 no-op

        int expired = service.sweep();

        assertThat(expired).isZero();
        verify(preemption, never()).returnSeats(any(), any()); // 이중 SADD = 오버셀 방지
        // 만료 0건이라 빈 맵으로 호출 — 어떤 카운터도 DECR 되지 않음(이중 DECR = 카운터 훼손 방지).
        verify(admissionService).leaveAll(Map.of());
    }

    @Test
    void sweep_만료대상_없으면_전이도_좌석반환도_없음() {
        when(reservationRepository.findExpiredHeldIds(any(), any())).thenReturn(List.of());

        assertThat(service.sweep()).isZero();
        verifyNoInteractions(txHelper, preemption); // 전이·좌석 반환 자체가 없음
        verify(admissionService).leaveAll(Map.of()); // 빈 배치라 어떤 카운터도 안 건드림(no-op)
    }
}
