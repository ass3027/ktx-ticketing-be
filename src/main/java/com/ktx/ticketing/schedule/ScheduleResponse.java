package com.ktx.ticketing.schedule;

import com.ktx.ticketing.domain.Schedule;

import java.time.LocalDateTime;

/**
 * 운행편 1건 응답. 엔티티를 컨트롤러 밖으로 직접 노출하지 않기 위한 읽기 DTO.
 *
 * <p>{@code remainingSeats}/{@code soldOut} 은 Redis 선점 풀({@code avail:} Set)의 크기에서 온
 * <b>약한 일관성</b> 값이다(T3-2). "잔여 1석"을 보고 들어가도 예매 임계영역에서 매진일 수 있으며 이는 정상 —
 * 표시는 안내일 뿐 최종 판정이 아니다. 매진은 보수적으로( {@code remainingSeats == 0} ⇒ {@code soldOut}) 표시한다.
 */
public record ScheduleResponse(
        Long scheduleId,
        String trainNumber,
        String trainName,
        String departureStation,
        String arrivalStation,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        int totalSeats,
        long remainingSeats,
        boolean soldOut
) {
    static ScheduleResponse from(Schedule s, long remainingSeats) {
        return new ScheduleResponse(
                s.getId(),
                s.getTrain().getTrainNumber(),
                s.getTrain().getName(),
                s.getDepartureStation(),
                s.getArrivalStation(),
                s.getDepartureTime(),
                s.getArrivalTime(),
                s.getTotalSeats(),
                remainingSeats,
                remainingSeats == 0
        );
    }
}
