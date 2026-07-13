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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 배치 발사(T4-13 After) — scheduleId별 좌석 묶음 1회 SADD(returnSeats)·만료 건수만큼 1회 DECRBY(leaveAll).
 */
@ExtendWith(MockitoExtension.class)
class BatchExpirySideEffectsTest {

    private static final long SCHEDULE_A = 1L, SEAT_A = 42L;
    private static final long SCHEDULE_B = 2L, SEAT_B = 43L, SEAT_B2 = 44L;

    @Mock SeatPreemption preemption;
    @Mock AdmissionService admissionService;
    @InjectMocks BatchExpirySideEffects sideEffects;

    @Test
    void release_scheduleId별_좌석묶음_SADD_와_건수만큼_DECRBY() {
        Map<Long, List<Long>> seats = new LinkedHashMap<>();
        seats.put(SCHEDULE_A, List.of(SEAT_A));
        seats.put(SCHEDULE_B, List.of(SEAT_B, SEAT_B2));

        sideEffects.release(seats);

        // scheduleId별 1회 SADD 로 좌석 묶음 반환 — 인자 전치·집계 누락 방지.
        verify(preemption).returnSeats(SCHEDULE_A, List.of(SEAT_A));
        verify(preemption).returnSeats(SCHEDULE_B, List.of(SEAT_B, SEAT_B2));
        // scheduleId별 만료 건수만큼 한 번에 DECRBY (A:1, B:2).
        verify(admissionService).leaveAll(Map.of(SCHEDULE_A, 1, SCHEDULE_B, 2));
    }

    @Test
    void release_빈맵이면_좌석반환만_없고_빈_DECRBY_호출() {
        sideEffects.release(Map.of());

        verifyNoInteractions(preemption);          // 반환할 좌석이 없음
        verify(admissionService).leaveAll(Map.of()); // 빈 배치 — 어떤 카운터도 안 건드림(no-op)
    }
}
