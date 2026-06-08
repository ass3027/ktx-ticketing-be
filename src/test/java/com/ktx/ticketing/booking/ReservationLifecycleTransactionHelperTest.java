package com.ktx.ticketing.booking;

import com.ktx.ticketing.booking.ReservationLifecycleTransactionHelper.Outcome;
import com.ktx.ticketing.domain.Reservation;
import com.ktx.ticketing.domain.ReservationRepository;
import com.ktx.ticketing.domain.ReservationStatus;
import com.ktx.ticketing.domain.Schedule;
import com.ktx.ticketing.domain.SeatInventory;
import com.ktx.ticketing.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 트랜잭션 헬퍼 검증 — 전이의 분기 판단(소유권 → 상태 → 멱등)과, 커밋 후 부수효과 대상 식별자 추출을 본다.
 * 실제 트랜잭션 커밋·낙관적 락(@Version) 동작은 통합 테스트(T3-11) 책임이고, 여기서는 라우팅 로직만 격리한다.
 *
 * <p>식별자 추출은 {@code reservation→seatInventory→schedule} 그래프 순회가 불가피하므로 그래프를 mock 한다.
 * 검증의 초점은 그래프 호출 자체가 아니라 결과 변형·전이 호출 여부·Outcome 에 담긴 식별자다.
 */
@ExtendWith(MockitoExtension.class)
class ReservationLifecycleTransactionHelperTest {

    private static final long RESERVATION_ID = 100L;
    private static final long OWNER_ID = 7L;
    private static final long OTHER_USER_ID = 9L; // 소유자 ≠ 요청자 경계
    private static final long SCHEDULE_ID = 1L;
    private static final long SEAT_ID = 42L;

    @Mock ReservationRepository reservationRepository;
    @InjectMocks ReservationLifecycleTransactionHelper helper;

    /** OWNER_ID 소유의 예약 mock 을 findById 에 연결. 상태·좌석 그래프는 각 테스트가 필요한 만큼 덧붙인다. */
    private Reservation foundReservationOwnedBy(long ownerId) {
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(ownerId);
        Reservation reservation = mock(Reservation.class);
        when(reservation.getUser()).thenReturn(owner);
        when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Optional.of(reservation));
        return reservation;
    }

    /** seatInventory→schedule 그래프 stub. seatId(getId)는 취소 경로에서만 쓰여 호출 측이 따로 stub. */
    private SeatInventory withSeatGraph(Reservation reservation) {
        Schedule schedule = mock(Schedule.class);
        when(schedule.getId()).thenReturn(SCHEDULE_ID);
        SeatInventory inventory = mock(SeatInventory.class);
        when(inventory.getSchedule()).thenReturn(schedule);
        when(reservation.getSeatInventory()).thenReturn(inventory);
        return inventory;
    }

    // --- confirm ---

    @Test
    void confirm_예약이_없으면_NotFound() {
        when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Optional.empty());

        Outcome outcome = helper.confirm(RESERVATION_ID, OWNER_ID);

        assertThat(outcome.result()).isInstanceOf(ReservationCommandResult.NotFound.class);
    }

    @Test
    void confirm_소유자가_아니면_Forbidden_전이없음() {
        Reservation reservation = foundReservationOwnedBy(OWNER_ID);

        Outcome outcome = helper.confirm(RESERVATION_ID, OTHER_USER_ID);

        assertThat(outcome.result()).isInstanceOf(ReservationCommandResult.Forbidden.class);
        verify(reservation, never()).confirm();
        assertThat(outcome.leaveScheduleId()).isNull();
    }

    @Test
    void confirm_HELD가_아니면_IllegalState_전이없음() {
        Reservation reservation = foundReservationOwnedBy(OWNER_ID);
        when(reservation.getStatus()).thenReturn(ReservationStatus.CONFIRMED); // 이미 확정

        Outcome outcome = helper.confirm(RESERVATION_ID, OWNER_ID);

        assertThat(outcome.result()).isInstanceOf(ReservationCommandResult.IllegalState.class);
        verify(reservation, never()).confirm();
    }

    @Test
    void confirm_HELD면_예약확정_좌석SOLD_활성슬롯_식별자만_반환() {
        Reservation reservation = foundReservationOwnedBy(OWNER_ID);
        when(reservation.getStatus()).thenReturn(ReservationStatus.HELD);
        SeatInventory inventory = withSeatGraph(reservation);

        Outcome outcome = helper.confirm(RESERVATION_ID, OWNER_ID);

        assertThat(outcome.result()).isInstanceOf(ReservationCommandResult.Success.class);
        verify(reservation).confirm();
        verify(inventory).confirm();
        assertThat(outcome.leaveScheduleId()).isEqualTo(SCHEDULE_ID);
        assertThat(outcome.returnSeatId()).isNull(); // 확정 좌석은 가용 풀로 반환하지 않음
    }

    // --- cancel ---

    @Test
    void cancel_이미_취소면_멱등_no_op_전이없음() {
        Reservation reservation = foundReservationOwnedBy(OWNER_ID);
        when(reservation.getStatus()).thenReturn(ReservationStatus.CANCELLED);

        Outcome outcome = helper.cancel(RESERVATION_ID, OWNER_ID);

        assertThat(outcome.result()).isInstanceOf(ReservationCommandResult.Success.class);
        verify(reservation, never()).cancel();
        assertThat(outcome.leaveScheduleId()).isNull();
        assertThat(outcome.returnSeatId()).isNull();
    }

    @Test
    void cancel_EXPIRED면_IllegalState_전이없음() {
        Reservation reservation = foundReservationOwnedBy(OWNER_ID);
        when(reservation.getStatus()).thenReturn(ReservationStatus.EXPIRED);

        Outcome outcome = helper.cancel(RESERVATION_ID, OWNER_ID);

        assertThat(outcome.result()).isInstanceOf(ReservationCommandResult.IllegalState.class);
        verify(reservation, never()).cancel();
    }

    @Test
    void cancel_HELD면_예약취소_좌석복구_좌석과_활성슬롯_식별자_반환() {
        Reservation reservation = foundReservationOwnedBy(OWNER_ID);
        when(reservation.getStatus()).thenReturn(ReservationStatus.HELD);
        SeatInventory inventory = withSeatGraph(reservation);
        when(inventory.getId()).thenReturn(SEAT_ID);

        Outcome outcome = helper.cancel(RESERVATION_ID, OWNER_ID);

        assertThat(outcome.result()).isInstanceOf(ReservationCommandResult.Success.class);
        verify(reservation).cancel();
        verify(inventory).release();
        assertThat(outcome.leaveScheduleId()).isEqualTo(SCHEDULE_ID);
        assertThat(outcome.returnSeatId()).isEqualTo(SEAT_ID); // 취소 좌석은 가용 풀로 반환
    }

    @Test
    void cancel_CONFIRMED도_취소가능_결제후_환불() {
        // 환불 경로 — HELD 뿐 아니라 CONFIRMED 도 취소 허용임을 못박는다(가드의 OR 조건 회귀 방지).
        Reservation reservation = foundReservationOwnedBy(OWNER_ID);
        when(reservation.getStatus()).thenReturn(ReservationStatus.CONFIRMED);
        SeatInventory inventory = withSeatGraph(reservation);
        when(inventory.getId()).thenReturn(SEAT_ID);

        Outcome outcome = helper.cancel(RESERVATION_ID, OWNER_ID);

        assertThat(outcome.result()).isInstanceOf(ReservationCommandResult.Success.class);
        verify(reservation).cancel();
        assertThat(outcome.returnSeatId()).isEqualTo(SEAT_ID);
    }
}
