package com.ktx.ticketing.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeatInventoryRepository extends JpaRepository<SeatInventory, Long> {

    @Query("SELECT si FROM SeatInventory si WHERE si.schedule.id = :scheduleId AND si.status = 'AVAILABLE'")
    List<SeatInventory> findAvailableByScheduleId(@Param("scheduleId") Long scheduleId);

    @Query("SELECT COUNT(si) FROM SeatInventory si WHERE si.schedule.id = :scheduleId AND si.status = :status")
    long countByScheduleIdAndStatus(@Param("scheduleId") Long scheduleId, @Param("status") SeatStatus status);
}
