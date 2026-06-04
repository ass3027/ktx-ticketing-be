package com.ktx.ticketing.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

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
    void expire_상태_EXPIRED_및_cancelledAt_설정() {
        Reservation reservation = Reservation.hold(new User(), new SeatInventory(), fixedClock());

        reservation.expire();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservation.getCancelledAt()).isNotNull();
    }
}
