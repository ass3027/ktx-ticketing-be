package com.ktx.ticketing.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {

    /** 고정 시각 — Clock.fixed로 now()를 결정화해 5분 HELD TTL을 정확히(isEqualTo) 단언한다. */
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 7, 1, 8, 0);

    private static Clock fixedClock() {
        return Clock.fixed(FIXED_NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    }

    @Test
    void hold_상태_HELD_및_만료시각_정확히_5분_후로_설정() {
        Reservation reservation = Reservation.hold(new User(), new SeatInventory(), fixedClock());

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(reservation.getHeldAt()).isEqualTo(FIXED_NOW);
        // expiresAt = heldAt + HELD_TTL (T1-1 HELD TTL 검증) — Clock 고정으로 오차 없이 단언
        assertThat(reservation.getExpiresAt()).isEqualTo(FIXED_NOW.plus(Reservation.HELD_TTL));
    }

    @Test
    void hold_좌석과_예약의_만료시각이_정확히_일치() {
        // C-2 핵심: hold()가 now()를 한 번만 호출해 좌석·예약이 같은 시각을 공유한다(skew=0).
        SeatInventory inventory = new SeatInventory();

        Reservation reservation = Reservation.hold(new User(), inventory, fixedClock());

        assertThat(inventory.getHeldAt()).isEqualTo(reservation.getHeldAt());
        assertThat(inventory.getExpiresAt()).isEqualTo(reservation.getExpiresAt());
        assertThat(inventory.getStatus()).isEqualTo(SeatStatus.HELD);
    }

    @Test
    void confirm_상태_CONFIRMED_및_confirmedAt_설정() {
        Reservation reservation = Reservation.hold(new User(), new SeatInventory(), fixedClock());

        reservation.confirm();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        // confirm() 은 LocalDateTime.now() 를 직접 부르므로 정확값 단언 불가 → isNotNull 로 한정.
        // T3-9b(Clock 단일화) 후 hold/markHeld 처럼 isEqualTo(고정시각) 으로 강화할 것.
        assertThat(reservation.getConfirmedAt()).isNotNull();
        assertThat(reservation.getCancelledAt()).isNull();
    }

    @Test
    void cancel_상태_CANCELLED_및_cancelledAt_설정() {
        Reservation reservation = Reservation.hold(new User(), new SeatInventory(), fixedClock());

        reservation.cancel();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isNotNull();
    }

    @Test
    void confirm_HELD가_아니면_예외_재확정_차단() {
        Reservation reservation = Reservation.hold(new User(), new SeatInventory(), fixedClock());
        reservation.confirm(); // HELD → CONFIRMED

        // 이미 CONFIRMED 인 예약 재확정은 상태머신 불변식 위반
        assertThatThrownBy(reservation::confirm).isInstanceOf(IllegalStateException.class);
        // 거부 시 상태는 그대로 — 던지기 전에 부분 변이가 없어야 한다
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void cancel_CONFIRMED에서도_가능_결제후_환불() {
        Reservation reservation = Reservation.hold(new User(), new SeatInventory(), fixedClock());
        reservation.confirm();

        reservation.cancel(); // CONFIRMED → CANCELLED (환불 경로)

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isNotNull();
    }

    @Test
    void cancel_HELD_CONFIRMED가_아니면_예외() {
        Reservation reservation = Reservation.hold(new User(), new SeatInventory(), fixedClock());
        reservation.expire(); // HELD → EXPIRED

        assertThatThrownBy(reservation::cancel).isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED); // 상태 유지
    }

    @Test
    void expire_상태_EXPIRED_및_cancelledAt_설정() {
        Reservation reservation = Reservation.hold(new User(), new SeatInventory(), fixedClock());

        reservation.expire();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservation.getCancelledAt()).isNotNull();
    }
}
