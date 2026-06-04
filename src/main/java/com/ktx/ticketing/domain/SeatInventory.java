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

    public void hold() {
        this.status = SeatStatus.HELD;
        this.heldAt = LocalDateTime.now();
        this.expiresAt = this.heldAt.plus(Reservation.HELD_TTL);
    }

    public void confirm() {
        this.status = SeatStatus.SOLD;
    }

    public void release() {
        this.status = SeatStatus.AVAILABLE;
        this.heldAt = null;
        this.expiresAt = null;
    }
}
