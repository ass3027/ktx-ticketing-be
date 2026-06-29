package com.ktx.ticketing.booking;

import com.ktx.ticketing.admission.AdmissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 건별 발사(T4-13 Before, 비교 기준) — 만료 좌석마다 SADD(returnSeat)·DECR(leave) 직렬 호출(2N RTT).
 * 결과(반환 좌석·차감 횟수)는 배치와 동일해야 하며 차이는 RTT 묶음 여부뿐임을 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class PerRowExpirySideEffectsTest {

    private static final long SCHEDULE_A = 1L, SEAT_A = 42L;
    private static final long SCHEDULE_B = 2L, SEAT_B = 43L, SEAT_B2 = 44L;

    @Mock SeatPreemption preemption;
    @Mock AdmissionService admissionService;
    @InjectMocks PerRowExpirySideEffects sideEffects;

    @Test
    void release_만료좌석마다_건별_SADD_와_건별_DECR() {
        Map<Long, List<Long>> seats = new LinkedHashMap<>();
        seats.put(SCHEDULE_A, List.of(SEAT_A));
        seats.put(SCHEDULE_B, List.of(SEAT_B, SEAT_B2));

        sideEffects.release(seats);

        verify(preemption).returnSeat(SCHEDULE_A, SEAT_A);
        verify(preemption).returnSeat(SCHEDULE_B, SEAT_B);
        verify(preemption).returnSeat(SCHEDULE_B, SEAT_B2);
        // 좌석 수만큼 DECR — A 1회, B 2회 (배치의 DECRBY 2 와 동일 결과, 호출만 건별).
        verify(admissionService).leave(SCHEDULE_A);
        verify(admissionService, times(2)).leave(SCHEDULE_B);
    }

    @Test
    void release_빈맵이면_아무것도_안한다() {
        sideEffects.release(Map.of());

        verifyNoInteractions(preemption, admissionService);
    }
}
