package com.ktx.ticketing.booking;

import com.ktx.ticketing.admission.AdmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 만료 부수효과 건별 발사 (T4-13 Before, 비교 기준). 만료된 좌석마다 SADD(returnSeat)·DECR(leave) 를 직렬
 * 호출해 Redis RTT 가 만료 건수에 비례(2N)한다 — L6 soak 에서 처리율 병목을 재현하는 측정 기준선.
 *
 * <p>{@code booking.expiry.batch-side-effects=false} 일 때만 활성. 대상·순서·정합성 규칙은
 * {@link BatchExpirySideEffects} 와 동일하며 차이는 RTT 묶음 여부뿐이다.
 */
@Component
@ConditionalOnProperty(name = "booking.expiry.batch-side-effects", havingValue = "false")
@RequiredArgsConstructor
public class PerRowExpirySideEffects implements ExpirySideEffects {

    private final SeatPreemption preemption;
    private final AdmissionService admissionService;

    @Override
    public void release(Map<Long, List<Long>> seatsBySchedule) {
        seatsBySchedule.forEach((scheduleId, seats) -> seats.forEach(seatId -> {
            preemption.returnSeat(scheduleId, seatId); // 건별 SADD
            admissionService.leave(scheduleId);        // 건별 DECR
        }));
    }
}
