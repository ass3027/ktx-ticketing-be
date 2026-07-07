package com.ktx.ticketing.schedule;

import com.ktx.ticketing.booking.SeatPreemption;
import com.ktx.ticketing.domain.Schedule;
import com.ktx.ticketing.domain.Train;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ScheduleQueryService 단위 테스트 — 오케스트레이터의 책임을 검증한다: 커서 페이징 경계(limit 클램프·
 * afterId 정규화·nextCursor)와 T4-5 ② 토글 라우팅(SCARD 를 tx 안/밖 어디로 보낼지). 실제 DB 조회·잔여석
 * 매핑은 {@link ScheduleQueryReader}(및 그 단위 테스트)의 책임이라 여기선 mock 으로 경계만 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class ScheduleQueryServiceTest {

    private static final String DEP = "서울";
    private static final String ARR = "부산";
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 7, 1, 9, 0);

    @Mock ScheduleQueryReader reader;
    @Mock SeatPreemption preemption;

    /**
     * 토글 2차원(②tx밖 × ③pipeline)으로 서비스를 조립한다. 커서 페이징 경계 테스트는 토글 무관이라
     * 기본(off,off) 경로로만 검증한다. ④ 캐시는 <b>disabled</b> 로 고정 — 이 테스트의 관심은 ②③ 컴퓨트
     * 라우팅이라, 캐시 켜짐은 별도 {@link ScheduleListCacheTest} 가 본다. disabled 면 서비스가 cache 를
     * 아예 호출하지 않으므로 mock 만 주입해도 안전하다.
     */
    private ScheduleQueryService service(boolean redisOutsideTx, boolean pipeline) {
        return new ScheduleQueryService(reader, preemption, new QueryProperties(redisOutsideTx, pipeline),
                new QueryCacheProperties(false, Duration.ofSeconds(1), 0), mock(ScheduleListCache.class));
    }

    // --- 커서 페이징 경계 (토글 무관 — 기본 off/off 경로로 검증) ---

    @Test
    void limit_미지정시_기본값_8로_조회() {
        service(false, false).search(DEP, ARR, FROM, null, null);

        assertThat(capturedPageSize()).isEqualTo(8);
    }

    @Test
    void limit_상한_초과시_100으로_클램프() {
        service(false, false).search(DEP, ARR, FROM, null, 999);

        assertThat(capturedPageSize()).isEqualTo(100);
    }

    @Test
    void limit_0이하시_1로_클램프() {
        service(false, false).search(DEP, ARR, FROM, null, 0);

        assertThat(capturedPageSize()).isEqualTo(1);
    }

    @Test
    void afterId_미지정시_0으로_정규화해_첫_페이지_조회() {
        service(false, false).search(DEP, ARR, FROM, null, 8);

        assertThat(capturedCursorId()).isZero();
    }

    @Test
    void afterId_지정시_그대로_위임() {
        service(false, false).search(DEP, ARR, FROM, 42L, 8);

        assertThat(capturedCursorId()).isEqualTo(42L);
    }

    @Test
    void 페이지를_꽉_채우면_마지막_항목으로_nextCursor_발급() {
        int limit = 3;
        LocalDateTime lastTime = FROM.plusHours(2);
        stubWithSeats(List.of(resp(1L, FROM), resp(2L, FROM.plusHours(1)), resp(7L, lastTime)));

        var result = service(false, false).search(DEP, ARR, FROM, null, limit);

        assertThat(result.items()).hasSize(3);
        assertThat(result.nextCursor()).isNotNull();
        assertThat(result.nextCursor().from()).isEqualTo(lastTime);
        assertThat(result.nextCursor().afterId()).isEqualTo(7L);
    }

    @Test
    void 페이지가_덜_차면_nextCursor_없음_마지막_페이지() {
        stubWithSeats(List.of(resp(1L, FROM), resp(2L, FROM.plusHours(1))));

        var result = service(false, false).search(DEP, ARR, FROM, null, 8);

        assertThat(result.items()).hasSize(2);
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void 빈_결과면_빈_리스트와_nextCursor_없음() {
        stubWithSeats(List.of());

        var result = service(false, false).search(DEP, ARR, FROM, null, 8);

        assertThat(result.items()).isEmpty();
        assertThat(result.nextCursor()).isNull();
    }

    // --- T4-5 ② tx밖 토글 라우팅 (pipeline off 고정) ---

    @Test
    void redisOutsideTx_off면_SCARD를_tx안에서_수행하는_경로로_위임() {
        service(false, false).search(DEP, ARR, FROM, null, 8);

        verify(reader).fetchPageWithSeats(eq(DEP), eq(ARR), eq(FROM), anyLong(), anyInt(), eq(false));
        verify(reader, never()).fetchPage(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void redisOutsideTx_on이면_tx밖에서_잔여석을_avail_크기로_채운다() {
        // scheduleOf 는 내부에서 when(...) 을 호출하므로 fetchPage stubbing 인자 안에 두면
        // 중첩 stubbing(UnfinishedStubbing)이 된다 → mock 을 먼저 조립해 변수로 넘긴다.
        List<Schedule> page = List.of(scheduleOf(1L, FROM), scheduleOf(2L, FROM.plusHours(1)));
        when(reader.fetchPage(eq(DEP), eq(ARR), eq(FROM), anyLong(), anyInt())).thenReturn(page);
        when(preemption.availableCount(1L)).thenReturn(42L);
        when(preemption.availableCount(2L)).thenReturn(0L);

        var result = service(true, false).search(DEP, ARR, FROM, null, 8);

        assertThat(result.items())
                .extracting(ScheduleResponse::scheduleId, ScheduleResponse::remainingSeats, ScheduleResponse::soldOut)
                .containsExactly(tuple(1L, 42L, false), tuple(2L, 0L, true));
        verify(reader, never()).fetchPageWithSeats(any(), any(), any(), anyLong(), anyInt(), anyBoolean());
    }

    // --- T4-5 ③ pipeline 토글 라우팅 (② 두 경로 각각에서 독립 동작) ---

    @Test
    void pipeline_on_tx안이면_배치플래그_true로_reader에_위임() {
        // tx안(②-off)의 직렬/배치 선택은 reader 내부라 서비스는 pipeline 플래그 전달만 책임진다.
        service(false, true).search(DEP, ARR, FROM, null, 8);

        verify(reader).fetchPageWithSeats(eq(DEP), eq(ARR), eq(FROM), anyLong(), anyInt(), eq(true));
    }

    @Test
    void pipeline_on_tx밖이면_배치_availableCounts_1회로_잔여석을_채운다() {
        List<Schedule> page = List.of(scheduleOf(1L, FROM), scheduleOf(2L, FROM.plusHours(1)));
        when(reader.fetchPage(eq(DEP), eq(ARR), eq(FROM), anyLong(), anyInt())).thenReturn(page);
        when(preemption.availableCounts(List.of(1L, 2L))).thenReturn(Map.of(1L, 42L, 2L, 0L));

        var result = service(true, true).search(DEP, ARR, FROM, null, 8);

        assertThat(result.items())
                .extracting(ScheduleResponse::scheduleId, ScheduleResponse::remainingSeats, ScheduleResponse::soldOut)
                .containsExactly(tuple(1L, 42L, false), tuple(2L, 0L, true));
        // pipeline = 배치 1회. 직렬 availableCount 는 호출되면 안 된다(N왕복 회귀 방지).
        verify(preemption, never()).availableCount(anyLong());
    }

    // --- helpers ---

    private void stubWithSeats(List<ScheduleResponse> items) {
        when(reader.fetchPageWithSeats(any(), any(), any(), anyLong(), anyInt(), anyBoolean())).thenReturn(items);
    }

    private int capturedPageSize() {
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(reader).fetchPageWithSeats(any(), any(), any(), anyLong(), captor.capture(), anyBoolean());
        return captor.getValue();
    }

    private long capturedCursorId() {
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(reader).fetchPageWithSeats(any(), any(), any(), captor.capture(), anyInt(), anyBoolean());
        return captor.getValue();
    }

    /** nextCursor 는 응답의 departureTime/scheduleId 만 참조하므로 나머지 필드는 대표값으로 채운다. */
    private static ScheduleResponse resp(long id, LocalDateTime departureTime) {
        return new ScheduleResponse(id, "KTX-001", "KTX", DEP, ARR,
                departureTime, departureTime.plusHours(2), 100, 10, false);
    }

    /** on 경로의 매핑(ScheduleResponse.from)이 train 을 참조하므로 train 을 실제 객체로 채운다. */
    private static Schedule scheduleOf(long id, LocalDateTime departureTime) {
        Schedule s = mock(Schedule.class);
        when(s.getId()).thenReturn(id);
        when(s.getDepartureTime()).thenReturn(departureTime);
        when(s.getTrain()).thenReturn(new Train("KTX 경부선", "KTX-001"));
        return s;
    }
}
