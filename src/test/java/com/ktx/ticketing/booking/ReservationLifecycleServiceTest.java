package com.ktx.ticketing.booking;

import com.ktx.ticketing.admission.AdmissionService;
import com.ktx.ticketing.booking.ReservationLifecycleTransactionHelper.Outcome;
import com.ktx.ticketing.domain.Reservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReservationLifecycleService(오케스트레이터) 검증 — 핵심 규칙은 "Redis 부수효과(좌석 반환 SADD·활성 슬롯
 * 반환)를 <b>실제 전이가 일어난 경우에만</b> 실행한다"는 정합성 결정이다. 헬퍼가 식별자를 채워 주면 부수효과를
 * 수행하고, 비워 주면(거절·멱등 no-op) 건드리지 않는다. DB 전이 자체는 헬퍼 책임이라 mock 으로 대체한다.
 */
@ExtendWith(MockitoExtension.class)
class ReservationLifecycleServiceTest {

    // 서로 다른 값으로 둬서 scheduleId/seatId 인자 전치 버그를 잡는다.
    private static final long RESERVATION_ID = 100L;
    private static final long USER_ID = 7L;
    private static final long SCHEDULE_ID = 1L;
    private static final long SEAT_ID = 42L;

    @Mock ReservationLifecycleTransactionHelper txHelper;
    @Mock SeatPreemption preemption;
    @Mock AdmissionService admissionService;

    @InjectMocks ReservationLifecycleService service;

    private static ReservationCommandResult.Success success() {
        return new ReservationCommandResult.Success(mock(Reservation.class)); // 통과용 더미
    }

    @Test
    void confirm_성공시_활성슬롯만_반환하고_좌석은_가용풀로_되돌리지_않음() {
        when(txHelper.confirm(RESERVATION_ID, USER_ID))
                .thenReturn(new Outcome(success(), SCHEDULE_ID, null));

        ReservationCommandResult result = service.confirm(RESERVATION_ID, USER_ID);

        assertThat(result).isInstanceOf(ReservationCommandResult.Success.class);
        verify(admissionService).leave(SCHEDULE_ID);
        verify(preemption, never()).returnSeat(anyLong(), anyLong()); // SOLD 좌석은 풀에 안 돌아감
    }

    @Test
    void cancel_성공시_좌석_가용풀_SADD_그리고_활성슬롯_반환() {
        when(txHelper.cancel(RESERVATION_ID, USER_ID))
                .thenReturn(new Outcome(success(), SCHEDULE_ID, SEAT_ID));

        ReservationCommandResult result = service.cancel(RESERVATION_ID, USER_ID);

        assertThat(result).isInstanceOf(ReservationCommandResult.Success.class);
        verify(preemption).returnSeat(SCHEDULE_ID, SEAT_ID);
        verify(admissionService).leave(SCHEDULE_ID);
    }

    @Test
    void cancel_멱등_no_op이면_부수효과_없음() {
        // 이미 취소된 예약 재취소 — 헬퍼가 식별자를 비워(sideEffectFree) 넘긴다
        when(txHelper.cancel(RESERVATION_ID, USER_ID))
                .thenReturn(Outcome.sideEffectFree(success()));

        ReservationCommandResult result = service.cancel(RESERVATION_ID, USER_ID);

        assertThat(result).isInstanceOf(ReservationCommandResult.Success.class);
        verify(preemption, never()).returnSeat(anyLong(), anyLong()); // 이중 SADD = 오버셀 방지
        verify(admissionService, never()).leave(anyLong());           // 이중 DECR = 과다 입장 방지
    }

    @Test
    void 거절_결과는_부수효과_없이_그대로_반환() {
        // 거절(NotFound/Forbidden/IllegalState)은 식별자 없이 와 Redis 무변경
        when(txHelper.confirm(RESERVATION_ID, USER_ID))
                .thenReturn(Outcome.sideEffectFree(new ReservationCommandResult.Forbidden()));

        ReservationCommandResult result = service.confirm(RESERVATION_ID, USER_ID);

        assertThat(result).isInstanceOf(ReservationCommandResult.Forbidden.class);
        verify(admissionService, never()).leave(anyLong());
        verify(preemption, never()).returnSeat(anyLong(), anyLong());
    }
}
