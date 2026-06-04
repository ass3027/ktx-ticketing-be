package com.ktx.ticketing.schedule;

import com.ktx.ticketing.domain.Schedule;

import java.time.LocalDateTime;

/**
 * 운행편 1건 응답. 엔티티를 컨트롤러 밖으로 직접 노출하지 않기 위한 읽기 DTO.
 *
 * <p>T3-2 에서 {@code remainingSeats}/{@code soldOut}(Redis 카운터, 약한 일관성) 필드가 추가된다.
 */
public record ScheduleResponse(
        Long scheduleId,
        String trainNumber,
        String trainName,
        String departureStation,
        String arrivalStation,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        int totalSeats
) {
    static ScheduleResponse from(Schedule s) {
        return new ScheduleResponse(
                s.getId(),
                s.getTrain().getTrainNumber(),
                s.getTrain().getName(),
                s.getDepartureStation(),
                s.getArrivalStation(),
                s.getDepartureTime(),
                s.getArrivalTime(),
                s.getTotalSeats()
        );
    }
}
