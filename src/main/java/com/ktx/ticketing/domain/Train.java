package com.ktx.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "train")
@Getter
@NoArgsConstructor
public class Train {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true, length = 50)
    private String trainNumber;

    public Train(String name, String trainNumber) {
        this.name = name;
        this.trainNumber = trainNumber;
    }
}
