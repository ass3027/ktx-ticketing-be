package com.ktx.ticketing.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class Reservation {

    /** HELD 상태의 수명. 좌석 상태머신(AVAILABLE→HELD→SOLD)의 핵심 도메인 규칙 — M1에서 5분으로 동결. */
    public static final Duration HELD_TTL = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_inventory_id", nullable = false, unique = true)
    private SeatInventory seatInventory;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(nullable = false)
    private LocalDateTime heldAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime confirmedAt;
    private LocalDateTime cancelledAt;

    /**
     * 예약 점유와 좌석 점유는 하나의 원자적 동작이다 — 이 진입점에서 시각을 한 번 계산해
     * Reservation·SeatInventory가 같은 heldAt/expiresAt을 공유한다(시각 단일 출처, skew 방지).
     */
    public static Reservation hold(User user, SeatInventory seatInventory, Clock clock) {
        LocalDateTime heldAt = LocalDateTime.now(clock);
        seatInventory.markHeld(heldAt);
        return Reservation.builder()
                .user(user)
                .seatInventory(seatInventory)
                .status(ReservationStatus.HELD)
                .heldAt(heldAt)
                .expiresAt(heldAt.plus(HELD_TTL))
                .build();
    }

    /** HELD → CONFIRMED. 상태머신 불변식 — 확정은 HELD 에서만. 이미 확정/취소/만료된 예약 재확정 차단. */
    public void confirm() {
        if (status != ReservationStatus.HELD) {
            throw new IllegalStateException("확정은 HELD 상태에서만 가능: " + status);
        }
        this.status = ReservationStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }

    /** HELD/CONFIRMED → CANCELLED. 미결제 취소·결제 후 환불 모두 허용. 만료/기취소 예약 취소 차단. */
    public void cancel() {
        if (status != ReservationStatus.HELD && status != ReservationStatus.CONFIRMED) {
            throw new IllegalStateException("취소는 HELD/CONFIRMED 상태에서만 가능: " + status);
        }
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
    }

    public void expire() {
        this.status = ReservationStatus.EXPIRED;
        this.cancelledAt = LocalDateTime.now();
    }
}
