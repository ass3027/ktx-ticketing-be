package com.ktx.ticketing.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    /**
     * 출발/도착역 + 커서 이후의 운행편을 출발시각 오름차순(동시각은 id 오름차순)으로 한 페이지 조회.
     *
     * <p>복합 커서 {@code (from, afterId)} 로 동일 출발시각 운행편의 누락·중복을 방지한다.
     * 첫 페이지는 호출 측이 {@code afterId = 0} 으로 정규화해 {@code departureTime >= from} 효과를 낸다.
     * {@code train} 은 응답에 trainNumber/name 이 필요하므로 fetch join 으로 N+1 을 회피한다.
     * 페이지 크기는 {@link Pageable} 로 제한한다.
     */
    @Query("""
            select s from Schedule s join fetch s.train
            where s.departureStation = :dep and s.arrivalStation = :arr
              and (s.departureTime > :from
                   or (s.departureTime = :from and s.id > :afterId))
            order by s.departureTime asc, s.id asc
            """)
    List<Schedule> findPageAfter(@Param("dep") String dep,
                                 @Param("arr") String arr,
                                 @Param("from") LocalDateTime from,
                                 @Param("afterId") Long afterId,
                                 Pageable pageable);
}
