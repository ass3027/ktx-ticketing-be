package com.ktx.ticketing.schedule;

import com.ktx.ticketing.domain.Schedule;
import com.ktx.ticketing.domain.ScheduleRepository;
import com.ktx.ticketing.domain.Train;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ScheduleQueryService 단위 테스트 — 커서 페이징 경계(limit 클램프·afterId 정규화·nextCursor 계산)와
 * 리포지토리 위임 인자를 검증한다. JPQL 자체(fetch join·복합 커서 동작)는 통합 테스트(T3-11)의 책임.
 */
@ExtendWith(MockitoExtension.class)
class ScheduleQueryServiceTest {

    private static final String DEP = "서울";
    private static final String ARR = "부산";
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 7, 1, 9, 0);

    @Mock ScheduleRepository scheduleRepository;

    private ScheduleQueryService service() {
        return new ScheduleQueryService(scheduleRepository);
    }

    /**
     * Schedule 을 mock 한다. nextCursor 계산엔 id/출발시각만 필요하지만, DTO 매핑(ScheduleResponse.from)이
     * train 을 참조하므로 NPE 를 피하려 train 도 실제 객체로 채운다.
     */
    private static Schedule scheduleOf(long id, LocalDateTime departureTime) {
        Schedule s = mock(Schedule.class);
        when(s.getId()).thenReturn(id);
        when(s.getDepartureTime()).thenReturn(departureTime);
        when(s.getTrain()).thenReturn(new Train("KTX 경부선", "KTX-001"));
        return s;
    }

    private void stubRepositoryReturns(List<Schedule> page) {
        when(scheduleRepository.findPageAfter(any(), any(), any(), any(), any())).thenReturn(page);
    }

    @Test
    void limit_미지정시_기본값_8로_조회() {
        stubRepositoryReturns(List.of());

        service().search(DEP, ARR, FROM, null, null);

        assertThat(capturedPageable().getPageSize()).isEqualTo(8);
    }

    @Test
    void limit_상한_초과시_100으로_클램프() {
        stubRepositoryReturns(List.of());

        service().search(DEP, ARR, FROM, null, 999);

        assertThat(capturedPageable().getPageSize()).isEqualTo(100);
    }

    @Test
    void limit_0이하시_1로_클램프() {
        stubRepositoryReturns(List.of());

        service().search(DEP, ARR, FROM, null, 0);

        assertThat(capturedPageable().getPageSize()).isEqualTo(1);
    }

    @Test
    void afterId_미지정시_0으로_정규화해_첫_페이지_조회() {
        stubRepositoryReturns(List.of());

        service().search(DEP, ARR, FROM, null, 8);

        ArgumentCaptor<Long> afterId = ArgumentCaptor.forClass(Long.class);
        org.mockito.Mockito.verify(scheduleRepository)
                .findPageAfter(eq(DEP), eq(ARR), eq(FROM), afterId.capture(), any());
        assertThat(afterId.getValue()).isZero();
    }

    @Test
    void afterId_지정시_그대로_위임() {
        stubRepositoryReturns(List.of());

        service().search(DEP, ARR, FROM, 42L, 8);

        ArgumentCaptor<Long> afterId = ArgumentCaptor.forClass(Long.class);
        org.mockito.Mockito.verify(scheduleRepository)
                .findPageAfter(eq(DEP), eq(ARR), eq(FROM), afterId.capture(), any());
        assertThat(afterId.getValue()).isEqualTo(42L);
    }

    @Test
    void 페이지를_꽉_채우면_마지막_항목으로_nextCursor_발급() {
        int limit = 3;
        LocalDateTime lastTime = FROM.plusHours(2);
        List<Schedule> full = List.of(
                scheduleOf(1L, FROM),
                scheduleOf(2L, FROM.plusHours(1)),
                scheduleOf(7L, lastTime)); // 마지막 항목 id=7
        stubRepositoryReturns(full);

        var result = service().search(DEP, ARR, FROM, null, limit);

        assertThat(result.items()).hasSize(3);
        assertThat(result.nextCursor()).isNotNull();
        assertThat(result.nextCursor().from()).isEqualTo(lastTime);
        assertThat(result.nextCursor().afterId()).isEqualTo(7L);
    }

    @Test
    void 페이지가_덜_차면_nextCursor_없음_마지막_페이지() {
        int limit = 8;
        List<Schedule> partial = List.of(scheduleOf(1L, FROM), scheduleOf(2L, FROM.plusHours(1)));
        stubRepositoryReturns(partial);

        var result = service().search(DEP, ARR, FROM, null, limit);

        assertThat(result.items()).hasSize(2);
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void 빈_결과면_빈_리스트와_nextCursor_없음() {
        stubRepositoryReturns(List.of());

        var result = service().search(DEP, ARR, FROM, null, 8);

        assertThat(result.items()).isEmpty();
        assertThat(result.nextCursor()).isNull();
    }

    private Pageable capturedPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(scheduleRepository)
                .findPageAfter(any(), any(), any(), any(), captor.capture());
        return captor.getValue();
    }
}
