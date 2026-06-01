package com.ktx.ticketing.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SeatInventoryTest {

    private SeatInventory inventory;

    @BeforeEach
    void setUp() {
        inventory = new SeatInventory();
    }

    @Test
    void hold_상태를_HELD로_변경하고_타임스탬프_설정() {
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

        inventory.hold(expiresAt);

        assertThat(inventory.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(inventory.getHeldAt()).isNotNull();
        assertThat(inventory.getExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void confirm_상태를_SOLD로_변경() {
        inventory.hold(LocalDateTime.now().plusMinutes(5));

        inventory.confirm();

        assertThat(inventory.getStatus()).isEqualTo(SeatStatus.SOLD);
    }

    @Test
    void release_상태를_AVAILABLE로_복구하고_타임스탬프_초기화() {
        inventory.hold(LocalDateTime.now().plusMinutes(5));

        inventory.release();

        assertThat(inventory.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(inventory.getHeldAt()).isNull();
        assertThat(inventory.getExpiresAt()).isNull();
    }
}
