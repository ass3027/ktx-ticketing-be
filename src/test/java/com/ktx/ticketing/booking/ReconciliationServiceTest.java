package com.ktx.ticketing.booking;

import com.ktx.ticketing.booking.ReconciliationService.DriftReport;
import com.ktx.ticketing.domain.ScheduleRepository;
import com.ktx.ticketing.domain.SeatInventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * reconcile 한 스케줄 보정의 핵심 결정 — <b>방향 비대칭 안전 원리</b>(설계노트 §4·§7)를 검증한다.
 * 가장 중요한 단언은 "missing 좌석이라도 최근 선점이면 되돌리지 않는다"(오버셀 방지)다.
 * Clock 은 고정해 grace 경계 판정을 결정적으로 만든다(FIRST-Repeatable).
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    private static final long SCHEDULE_ID = 7L;
    private static final Duration GRACE = Duration.ofMinutes(5);
    private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

    @Mock private ScheduleRepository scheduleRepository;
    @Mock private SeatInventoryRepository seatInventoryRepository;
    @Mock private SeatPreemption preemption;

    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        // properties·clock 은 값/시간 객체라 실제 인스턴스를 쓴다(Mock only what you own).
        ReconcileProperties properties = new ReconcileProperties(Duration.ofSeconds(60), GRACE);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ReconciliationService(
                scheduleRepository, seatInventoryRepository, preemption, properties, clock);
    }

    @Test
    void 드리프트_없으면_아무것도_보정하지_않는다() {
        when(seatInventoryRepository.findAvailableIdsByScheduleId(SCHEDULE_ID)).thenReturn(List.of(1L, 2L));
        when(preemption.availableSeatIds(SCHEDULE_ID)).thenReturn(Set.of(1L, 2L));

        DriftReport report = service.reconcileSchedule(SCHEDULE_ID);

        verify(preemption, never()).removeSeat(anyLong(), anyLong());
        verify(preemption, never()).returnSeat(anyLong(), anyLong());
        assertThat(report.staleRemoved()).isZero();
        assertThat(report.missingAdded()).isZero();
        assertThat(report.missingSkipped()).isZero();
    }

    @Test
    void stale_좌석은_풀에서_제거한다() {
        // Redis엔 1,2 있으나 DB는 1만 AVAILABLE → 2는 stale(DB가 HELD/SOLD로 본 좌석이 풀에 남음)
        when(seatInventoryRepository.findAvailableIdsByScheduleId(SCHEDULE_ID)).thenReturn(List.of(1L));
        when(preemption.availableSeatIds(SCHEDULE_ID)).thenReturn(Set.of(1L, 2L));

        DriftReport report = service.reconcileSchedule(SCHEDULE_ID);

        verify(preemption).removeSeat(SCHEDULE_ID, 2L);
        verify(preemption, never()).returnSeat(anyLong(), anyLong());
        assertThat(report.staleRemoved()).isEqualTo(1);
    }

    @Test
    void missing_좌석이_선점흔적_없으면_풀로_되돌린다() {
        when(seatInventoryRepository.findAvailableIdsByScheduleId(SCHEDULE_ID)).thenReturn(List.of(5L));
        when(preemption.availableSeatIds(SCHEDULE_ID)).thenReturn(Set.of());
        when(preemption.preemptedAtMillis(SCHEDULE_ID, 5L)).thenReturn(0L); // 선점 기록 없음 = in-flight 아님

        DriftReport report = service.reconcileSchedule(SCHEDULE_ID);

        verify(preemption).returnSeat(SCHEDULE_ID, 5L);
        assertThat(report.missingAdded()).isEqualTo(1);
        assertThat(report.missingSkipped()).isZero();
    }

    @Test
    void missing_좌석이_grace_밖_선점이면_풀로_되돌린다() {
        long staleTs = NOW.toEpochMilli() - GRACE.toMillis() - 1; // 선점이 grace 보다 오래됨 → 진짜 드리프트
        when(seatInventoryRepository.findAvailableIdsByScheduleId(SCHEDULE_ID)).thenReturn(List.of(5L));
        when(preemption.availableSeatIds(SCHEDULE_ID)).thenReturn(Set.of());
        when(preemption.preemptedAtMillis(SCHEDULE_ID, 5L)).thenReturn(staleTs);

        DriftReport report = service.reconcileSchedule(SCHEDULE_ID);

        verify(preemption).returnSeat(SCHEDULE_ID, 5L);
        assertThat(report.missingAdded()).isEqualTo(1);
    }

    @Test
    void missing_좌석이라도_grace_이내_최근_선점이면_되돌리지_않는다_오버셀_방지() {
        long recentTs = NOW.toEpochMilli() - 1_000; // 1초 전 = grace 이내 = [SREM~커밋] in-flight
        when(seatInventoryRepository.findAvailableIdsByScheduleId(SCHEDULE_ID)).thenReturn(List.of(5L));
        when(preemption.availableSeatIds(SCHEDULE_ID)).thenReturn(Set.of());
        when(preemption.preemptedAtMillis(SCHEDULE_ID, 5L)).thenReturn(recentTs);

        DriftReport report = service.reconcileSchedule(SCHEDULE_ID);

        // 되살리면 진행 중인 정상 예매의 좌석을 두 명이 갖게 됨 = 오버셀
        verify(preemption, never()).returnSeat(anyLong(), anyLong());
        assertThat(report.missingAdded()).isZero();
        assertThat(report.missingSkipped()).isEqualTo(1);
    }
}
