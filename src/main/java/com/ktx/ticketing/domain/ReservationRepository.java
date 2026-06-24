package com.ktx.ticketing.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    long countBySeatInventoryId(Long seatInventoryId);

    /**
     * 만료 대상(HELD 인데 만료시각이 지난) 예약 id 를 오래된 순으로 조회. 만료 스케줄러(T3-9)가 사용.
     * id 만 가져와 각 건을 독립 트랜잭션으로 처리하고, {@code Pageable} 로 sweep 당 처리량을 bound 한다(soak L6).
     */
    @Query("SELECT r.id FROM Reservation r WHERE r.status = 'HELD' AND r.expiresAt < :now ORDER BY r.expiresAt")
    List<Long> findExpiredHeldIds(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * 예약 + 좌석을 한 번에 로드(fetch join). 만료 전이는 seatInventory 를 반드시 건드리므로,
     * {@code findById} 후 LAZY 재조회(N+1)를 피해 SELECT 를 1회로 줄인다. schedule 은 프록시 id 접근이라 추가 조회 없음.
     */
    @Query("SELECT r FROM Reservation r JOIN FETCH r.seatInventory WHERE r.id = :id")
    Optional<Reservation> findWithSeatById(@Param("id") Long id);

    /**
     * 만료시각이 지났는데 아직 HELD 인 예약 수(U-2 정합성 audit). 0 이 정상 — 0 보다 크면
     * 만료 스케줄러(T3-9)가 밀려 좌석이 회수되지 않은 잔재다. post_run_check.sql ⑤와 동일.
     */
    @Query("SELECT COUNT(r) FROM Reservation r WHERE r.status = 'HELD' AND r.expiresAt < :now")
    long countExpiredHeld(@Param("now") LocalDateTime now);

    /**
     * 같은 좌석에 활성(HELD/CONFIRMED) 예약이 2건 이상인 좌석 수(U-2 정합성 audit). 0 이 정상 —
     * 0 보다 크면 한 좌석을 둘 이상이 점유한 오버셀/중복이다. post_run_check.sql ②와 동일.
     */
    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT r.seat_inventory_id
                FROM reservation r
                WHERE r.status IN ('HELD', 'CONFIRMED')
                GROUP BY r.seat_inventory_id
                HAVING COUNT(*) > 1
            ) dup
            """, nativeQuery = true)
    long countSeatsWithMultipleActive();
}
