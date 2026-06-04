package com.ktx.ticketing.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import java.time.temporal.ChronoUnit;

class ReservationTest {

    @Test
    void hold_상태_HELD_및_만료시각_5분으로_설정() {
        LocalDateTime before = LocalDateTime.now();
        Reservation reservation = Reservation.hold(new User(), new SeatInventory());
        LocalDateTime after = LocalDateTime.now();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(reservation.getHeldAt()).isBetween(before, after);
        // expiresAt = heldAt + HELD_TTL (T1-1 HELD TTL 검증)
        assertThat(reservation.getExpiresAt())
            .isCloseTo(reservation.getHeldAt().plus(Reservation.HELD_TTL), within(1, ChronoUnit.SECONDS));
    }

    @Test
    void confirm_상태_CONFIRMED_및_confirmedAt_설정() {
        Reservation reservation = Reservation.hold(new User(), new SeatInventory());

        reservation.confirm();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getConfirmedAt()).isNotNull();
        assertThat(reservation.getCancelledAt()).isNull();
    }

    @Test
    void cancel_상태_CANCELLED_및_cancelledAt_설정() {
        Reservation reservation = Reservation.hold(new User(), new SeatInventory());

        reservation.cancel();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isNotNull();
    }

    @Test
    void expire_상태_EXPIRED_및_cancelledAt_설정() {
        Reservation reservation = Reservation.hold(new User(), new SeatInventory());

        reservation.expire();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservation.getCancelledAt()).isNotNull();
    }

    @Test
    void hold_HELD_TTL_5분_상수_검증() {
        Reservation reservation = Reservation.hold(new User(), new SeatInventory());

        long diffMinutes = ChronoUnit.MINUTES.between(reservation.getHeldAt(), reservation.getExpiresAt());
        assertThat(diffMinutes).isEqualTo(5);
    }
}
