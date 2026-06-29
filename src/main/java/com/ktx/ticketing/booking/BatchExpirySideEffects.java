package com.ktx.ticketing.booking;

import com.ktx.ticketing.admission.AdmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 만료 부수효과 배치 발사 (T4-13 After). scheduleId별로 좌석 묶음 1회 SADD(returnSeats)·만료 건수만큼 1회
 * DECRBY(leaveAll) 로 접어, sweep 의 Redis RTT 를 만료 건수가 아닌 schedule 수로 bound 한다.
 *
 * <p>{@code batch-side-effects} 미지정 시 기본값(matchIfMissing) — 운영 기본은 배치다.
 */
@Component
@ConditionalOnProperty(name = "booking.expiry.batch-side-effects", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class BatchExpirySideEffects implements ExpirySideEffects {

    private final SeatPreemption preemption;
    private final AdmissionService admissionService;

    @Override
    public void release(Map<Long, List<Long>> seatsBySchedule) {
        seatsBySchedule.forEach(preemption::returnSeats);                 // scheduleId별 1 SADD
        Map<Long, Integer> countBySchedule = new LinkedHashMap<>();
        seatsBySchedule.forEach((scheduleId, seats) -> countBySchedule.put(scheduleId, seats.size()));
        admissionService.leaveAll(countBySchedule);                       // scheduleId별 1 DECRBY
    }
}
