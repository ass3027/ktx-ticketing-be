package com.ktx.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "seat",
    uniqueConstraints = @UniqueConstraint(columnNames = {"train_id", "car_number", "seat_number"})
)
@Getter
@NoArgsConstructor
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "train_id", nullable = false)
    private Train train;

    @Column(nullable = false)
    private int carNumber;

    @Column(nullable = false, length = 10)
    private String seatNumber;

    public Seat(Train train, int carNumber, String seatNumber) {
        this.train = train;
        this.carNumber = carNumber;
        this.seatNumber = seatNumber;
    }
}
