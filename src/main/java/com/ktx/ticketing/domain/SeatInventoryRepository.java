package com.ktx.ticketing.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeatInventoryRepository extends JpaRepository<SeatInventory, Long> {

    @Query("SELECT si FROM SeatInventory si WHERE si.schedule.id = :scheduleId AND si.status = 'AVAILABLE'")
    List<SeatInventory> findAvailableByScheduleId(@Param("scheduleId") Long scheduleId);

    /**
     * 스케줄의 AVAILABLE 좌석 id만 프로젝션(엔티티 미로드). T3-10 reconcile 가 가용 풀(Redis)과 diff 하는
     * DB(SoT) 기준 집합 — 좌석 수가 많아도 id만 읽어 메모리/매핑 비용을 낮춘다.
     */
    @Query("SELECT si.id FROM SeatInventory si WHERE si.schedule.id = :scheduleId AND si.status = 'AVAILABLE'")
    List<Long> findAvailableIdsByScheduleId(@Param("scheduleId") Long scheduleId);

    @Query("SELECT COUNT(si) FROM SeatInventory si WHERE si.schedule.id = :scheduleId AND si.status = :status")
    long countByScheduleIdAndStatus(@Param("scheduleId") Long scheduleId, @Param("status") SeatStatus status);
}
