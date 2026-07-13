package com.ktx.ticketing.schedule;

import com.ktx.ticketing.booking.SeatPreemption;
import com.ktx.ticketing.domain.Schedule;
import com.ktx.ticketing.domain.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 운행편 리스트 조회의 <b>트랜잭션 DB 단위</b>. {@link ScheduleQueryService}(비트랜잭션 오케스트레이터)와
 * 분리한 이유: {@code @Transactional} 은 프록시로 기동 시 경계가 고정돼 런타임 토글로 켜고 끌 수 없다 →
 * SCARD 를 tx 안/밖 어디서 돌릴지(T4-5 ②) 선택하려면 tx 경계를 별도 빈 메서드로 노출해야 한다.
 */
@Component
@RequiredArgsConstructor
class ScheduleQueryReader {

    private final ScheduleRepository scheduleRepository;
    private final SeatPreemption preemption;

    /**
     * ②-on: DB 페이지만 조회하고 트랜잭션 종료. {@code train} 은 fetch join 으로 이미 로딩돼 detach 후에도
     * 접근 안전 → 잔여석 SCARD 는 호출측이 tx 밖에서 수행(커넥션이 Redis 왕복을 감싸지 않음).
     */
    @Transactional(readOnly = true)
    List<Schedule> fetchPage(String dep, String arr, LocalDateTime from, long cursorId, int pageSize) {
        return scheduleRepository.findPageAfter(dep, arr, from, cursorId, PageRequest.of(0, pageSize));
    }

    /**
     * ②-off(Before): 잔여석 SCARD 를 트랜잭션 <b>안</b>에서 수행 — 커넥션이 Redis 왕복 동안 점유된다.
     * 이 점유시간(Hikari usage)이 ② 최적화의 Before 기준선이다. {@code pipeline} 로 tx 안에서도 직렬(N왕복)/
     * 배치(1왕복, ③)를 고른다 — A3(baseline 위 pipeline 단독)는 이 경로(tx안+pipeline)로 측정한다.
     */
    @Transactional(readOnly = true)
    List<ScheduleResponse> fetchPageWithSeats(String dep, String arr, LocalDateTime from,
                                              long cursorId, int pageSize, boolean pipeline) {
        List<Schedule> page = fetchPage(dep, arr, from, cursorId, pageSize);
        return pipeline ? toResponsesBatched(page, preemption) : toResponses(page, preemption);
    }

    /** ③-off: 페이지 엔티티 → 응답 DTO(잔여석은 좌석별 SCARD 직렬 N회). 매핑을 tx 안/밖 양쪽에서 공유. */
    static List<ScheduleResponse> toResponses(List<Schedule> page, SeatPreemption preemption) {
        return page.stream()
                .map(s -> ScheduleResponse.from(s, preemption.availableCount(s.getId())))
                .toList();
    }

    /**
     * ③-on: 페이지 전체 잔여석을 파이프라인 1회 왕복({@code availableCounts})으로 채운다(N RTT → 1 RTT).
     * 값·매진 판정은 {@link #toResponses} 와 동일 — SCARD 를 <b>묶는 방식</b>만 다르다. tx 안/밖 양쪽 공유.
     */
    static List<ScheduleResponse> toResponsesBatched(List<Schedule> page, SeatPreemption preemption) {
        List<Long> ids = page.stream().map(Schedule::getId).toList();
        var counts = preemption.availableCounts(ids);
        return page.stream()
                .map(s -> ScheduleResponse.from(s, counts.getOrDefault(s.getId(), 0L)))
                .toList();
    }
}
