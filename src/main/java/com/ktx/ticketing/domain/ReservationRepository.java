package com.ktx.ticketing.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    long countBySeatInventoryId(Long seatInventoryId);

    /**
     * 만료 대상(HELD 인데 만료시각이 지난) 예약 id 를 오래된 순으로 조회. 만료 스케줄러(T3-9)가 사용.
     * id 만 가져와 각 건을 독립 트랜잭션으로 처리하고, {@code Pageable} 로 sweep 당 처리량을 bound 한다(soak L6).
     */
    @Query("SELECT r.id FROM Reservation r WHERE r.status = 'HELD' AND r.expiresAt < :now ORDER BY r.expiresAt")
    List<Long> findExpiredHeldIds(@Param("now") LocalDateTime now, Pageable pageable);
}
