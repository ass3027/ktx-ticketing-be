package com.ktx.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor
public class Reservation {

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

    public static Reservation hold(User user, SeatInventory seatInventory, int heldTtlMinutes) {
        Reservation r = new Reservation();
        r.user = user;
        r.seatInventory = seatInventory;
        r.status = ReservationStatus.HELD;
        r.heldAt = LocalDateTime.now();
        r.expiresAt = r.heldAt.plusMinutes(heldTtlMinutes);
        return r;
    }

    public void confirm() {
        this.status = ReservationStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }

    public void cancel() {
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
    }

    public void expire() {
        this.status = ReservationStatus.EXPIRED;
        this.cancelledAt = LocalDateTime.now();
    }
}
