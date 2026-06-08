package com.ktx.ticketing.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatInventoryTest {

    private static final LocalDateTime HELD_AT = LocalDateTime.of(2026, 7, 1, 8, 0);

    private SeatInventory inventory;

    @BeforeEach
    void setUp() {
        inventory = new SeatInventory();
    }

    @Test
    void markHeld_상태를_HELD로_변경하고_만료시각을_주입시각_기준_HELD_TTL로_설정() {
        inventory.markHeld(HELD_AT);

        assertThat(inventory.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(inventory.getHeldAt()).isEqualTo(HELD_AT);
        // 시각은 호출자가 주입 — SeatInventory는 now()를 부르지 않으므로 오차 없이 단언
        assertThat(inventory.getExpiresAt()).isEqualTo(HELD_AT.plus(Reservation.HELD_TTL));
    }

    @Test
    void confirm_상태를_SOLD로_변경() {
        inventory.markHeld(HELD_AT);

        inventory.confirm();

        assertThat(inventory.getStatus()).isEqualTo(SeatStatus.SOLD);
    }

    @Test
    void release_상태를_AVAILABLE로_복구하고_타임스탬프_초기화() {
        inventory.markHeld(HELD_AT);

        inventory.release();

        assertThat(inventory.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(inventory.getHeldAt()).isNull();
        assertThat(inventory.getExpiresAt()).isNull();
    }

    @Test
    void confirm_HELD가_아니면_예외() {
        // 갓 생성된 좌석은 AVAILABLE — markHeld 없이 SOLD 전이 불가
        assertThatThrownBy(inventory::confirm).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void release_SOLD에서도_AVAILABLE로_복구_결제후_취소() {
        inventory.markHeld(HELD_AT);
        inventory.confirm(); // HELD → SOLD

        inventory.release(); // SOLD → AVAILABLE (환불 시 좌석 복구)

        assertThat(inventory.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(inventory.getHeldAt()).isNull();
    }

    @Test
    void release_이미_AVAILABLE이면_예외_이중반환_차단() {
        // 이중 release 는 가용 풀에 좌석을 중복 투입(SADD)해 오버셀로 이어지므로 차단
        assertThatThrownBy(inventory::release).isInstanceOf(IllegalStateException.class);
    }
}
