package com.ktx.ticketing.schedule;

import com.ktx.ticketing.booking.SeatPreemption;
import com.ktx.ticketing.domain.Schedule;
import com.ktx.ticketing.domain.ScheduleRepository;
import com.ktx.ticketing.domain.Train;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ScheduleQueryReader 단위 테스트 — 트랜잭션 DB 단위의 두 책임을 검증한다:
 * (1) {@code fetchPageWithSeats} 가 각 운행편의 잔여석을 avail Set 크기(SCARD)로 매핑,
 * (2) 리포지토리에 첫 페이지(page 0) + 요청 크기로 위임. 트랜잭션 경계(SCARD 가 tx 안에서 도는지)는
 * 프록시/런타임 관심사라 단위 테스트 대상이 아니며 — ② 효과는 L3 의 Hikari usage 로 측정한다.
 */
@ExtendWith(MockitoExtension.class)
class ScheduleQueryReaderTest {

    private static final String DEP = "서울";
    private static final String ARR = "부산";
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 7, 1, 9, 0);

    @Mock ScheduleRepository scheduleRepository;
    @Mock SeatPreemption preemption;
    @InjectMocks ScheduleQueryReader reader;

    @Test
    void fetchPageWithSeats_직렬_각_운행편_잔여석을_avail_Set_크기로_채우고_0이면_매진() {
        Schedule a = scheduleOf(1L, FROM);
        Schedule b = scheduleOf(2L, FROM.plusHours(1));
        when(scheduleRepository.findPageAfter(any(), any(), any(), any(), any())).thenReturn(List.of(a, b));
        when(preemption.availableCount(1L)).thenReturn(7L);
        when(preemption.availableCount(2L)).thenReturn(0L);

        List<ScheduleResponse> items = reader.fetchPageWithSeats(DEP, ARR, FROM, 0L, 8, false);

        assertThat(items)
                .extracting(ScheduleResponse::scheduleId, ScheduleResponse::remainingSeats, ScheduleResponse::soldOut)
                .containsExactly(tuple(1L, 7L, false), tuple(2L, 0L, true));
    }

    @Test
    void fetchPageWithSeats_pipeline이면_배치_availableCounts로_잔여석을_채우고_직렬은_호출안함() {
        Schedule a = scheduleOf(1L, FROM);
        Schedule b = scheduleOf(2L, FROM.plusHours(1));
        when(scheduleRepository.findPageAfter(any(), any(), any(), any(), any())).thenReturn(List.of(a, b));
        when(preemption.availableCounts(List.of(1L, 2L))).thenReturn(Map.of(1L, 7L, 2L, 0L));

        List<ScheduleResponse> items = reader.fetchPageWithSeats(DEP, ARR, FROM, 0L, 8, true);

        assertThat(items)
                .extracting(ScheduleResponse::scheduleId, ScheduleResponse::remainingSeats, ScheduleResponse::soldOut)
                .containsExactly(tuple(1L, 7L, false), tuple(2L, 0L, true));
        // ③ pipeline = 배치 1회. 직렬 availableCount(N왕복)로 회귀하면 안 된다.
        verify(preemption, never()).availableCount(anyLong());
    }

    @Test
    void fetchPage_첫_페이지와_요청_크기로_리포지토리에_위임() {
        when(scheduleRepository.findPageAfter(any(), any(), any(), any(), any())).thenReturn(List.of());

        reader.fetchPage(DEP, ARR, FROM, 42L, 8);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(scheduleRepository).findPageAfter(any(), any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(8);
    }

    private static Schedule scheduleOf(long id, LocalDateTime departureTime) {
        Schedule s = mock(Schedule.class);
        when(s.getId()).thenReturn(id);
        when(s.getDepartureTime()).thenReturn(departureTime);
        when(s.getTrain()).thenReturn(new Train("KTX 경부선", "KTX-001"));
        return s;
    }
}
