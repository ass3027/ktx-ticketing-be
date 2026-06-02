package com.ktx.ticketing.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    long countBySeatInventoryId(Long seatInventoryId);
}
