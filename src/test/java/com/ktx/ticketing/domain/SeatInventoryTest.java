package com.ktx.ticketing.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SeatInventoryTest {

    private SeatInventory inventory;

    @BeforeEach
    void setUp() {
        inventory = new SeatInventory();
    }

    @Test
    void hold_상태를_HELD로_변경하고_만료시각_HELD_TTL로_설정() {
        inventory.hold();

        assertThat(inventory.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(inventory.getHeldAt()).isNotNull();
        // expiresAt = heldAt + HELD_TTL (Reservation 도메인 규칙 공유)
        assertThat(inventory.getExpiresAt())
            .isCloseTo(inventory.getHeldAt().plus(Reservation.HELD_TTL), within(1, ChronoUnit.SECONDS));
    }

    @Test
    void confirm_상태를_SOLD로_변경() {
        inventory.hold();

        inventory.confirm();

        assertThat(inventory.getStatus()).isEqualTo(SeatStatus.SOLD);
    }

    @Test
    void release_상태를_AVAILABLE로_복구하고_타임스탬프_초기화() {
        inventory.hold();

        inventory.release();

        assertThat(inventory.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(inventory.getHeldAt()).isNull();
        assertThat(inventory.getExpiresAt()).isNull();
    }
}
