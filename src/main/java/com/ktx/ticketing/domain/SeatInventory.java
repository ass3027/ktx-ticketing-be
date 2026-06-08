package com.ktx.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "seat_inventory",
    uniqueConstraints = @UniqueConstraint(columnNames = {"schedule_id", "seat_id"}),
    indexes = @Index(name = "idx_schedule_status", columnList = "schedule_id, status")
)
@Getter
@NoArgsConstructor
public class SeatInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Version
    private int version;

    private LocalDateTime heldAt;
    private LocalDateTime expiresAt;

    public SeatInventory(Schedule schedule, Seat seat) {
        this.schedule = schedule;
        this.seat = seat;
        this.status = SeatStatus.AVAILABLE;
    }

    /**
     * 좌석을 HELD로 전이한다. 시각은 호출자(Reservation.hold)가 계산해 주입한다 —
     * SeatInventory가 직접 now()를 부르지 않으므로 예약과 좌석의 만료시각이 항상 일치한다.
     */
    public void markHeld(LocalDateTime heldAt) {
        this.status = SeatStatus.HELD;
        this.heldAt = heldAt;
        this.expiresAt = heldAt.plus(Reservation.HELD_TTL);
    }

    /** HELD → SOLD (결제 확정). 좌석 상태머신의 종착 전이 — HELD 에서만 허용. */
    public void confirm() {
        if (status != SeatStatus.HELD) {
            throw new IllegalStateException("좌석 확정(SOLD)은 HELD 상태에서만 가능: " + status);
        }
        this.status = SeatStatus.SOLD;
    }

    /**
     * HELD/SOLD → AVAILABLE (취소/만료 복구). 이미 AVAILABLE 인 좌석의 재반환을 막는다 —
     * 가용 풀에 좌석을 중복 투입하면(SADD) 오버셀로 이어지므로 이중 release 는 불변식 위반.
     */
    public void release() {
        if (status == SeatStatus.AVAILABLE) {
            throw new IllegalStateException("이미 AVAILABLE 인 좌석은 반환할 수 없음");
        }
        this.status = SeatStatus.AVAILABLE;
        this.heldAt = null;
        this.expiresAt = null;
    }
}
