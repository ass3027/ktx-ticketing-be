package com.ktx.ticketing.schedule;

import com.ktx.ticketing.booking.SeatPreemption;
import com.ktx.ticketing.domain.Schedule;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 운행편 리스트 조회(읽기 경로)의 오케스트레이터. 커서 페이징 경계 — limit 클램프, afterId 정규화,
 * nextCursor 계산 — 를 담당한다. DB 조회는 트랜잭션 단위인 {@link ScheduleQueryReader} 에 위임하고,
 * 잔여석 SCARD 를 트랜잭션 안/밖 어디서 돌릴지(T4-5 ②)는 {@link QueryProperties#redisOutsideTx()} 로,
 * SCARD 를 직렬 N회/파이프라인 1회 중 무엇으로 묶을지(T4-5 ③)는 {@link QueryProperties#pipeline()} 로
 * 분기한다. 두 토글은 독립이라 4조합 모두 동작한다(값·매진 판정은 조합 불변).
 *
 * <p>자신은 {@code @Transactional} 을 걸지 <b>않는다</b> — 토글에 따라 tx 경계(DB 조회만)와 SCARD 를
 * 분리해야 하는데, 프록시 기반 {@code @Transactional} 은 런타임에 켜고 끌 수 없기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class ScheduleQueryService {

    static final int DEFAULT_LIMIT = 8;
    static final int MAX_LIMIT = 100;
    /** 첫 페이지(afterId 미지정)를 departureTime >= from 으로 만들기 위한 하한 정규화 값. */
    static final long FIRST_PAGE_AFTER_ID = 0L;

    private final ScheduleQueryReader reader;
    private final SeatPreemption preemption;
    private final QueryProperties queryProperties;

    public ScheduleListResponse search(String dep, String arr, LocalDateTime from,
                                       @Nullable Long afterId, @Nullable Integer limit) {
        int pageSize = clampLimit(limit);
        long cursorId = (afterId != null) ? afterId : FIRST_PAGE_AFTER_ID;

        List<ScheduleResponse> items = queryProperties.redisOutsideTx()
                ? searchRedisOutsideTx(dep, arr, from, cursorId, pageSize)
                : reader.fetchPageWithSeats(dep, arr, from, cursorId, pageSize, queryProperties.pipeline());
        return new ScheduleListResponse(items, nextCursor(items, pageSize));
    }

    /**
     * ②-on: DB 페이지 조회로 tx 를 좁힌 뒤, 잔여석 SCARD 는 tx 밖에서(커넥션 반환 후) 수행한다.
     * ③({@code pipeline})에 따라 tx 밖에서도 직렬(N왕복)/배치(1왕복)를 고른다 — C3(②위 pipeline 누적).
     */
    private List<ScheduleResponse> searchRedisOutsideTx(String dep, String arr, LocalDateTime from,
                                                        long cursorId, int pageSize) {
        List<Schedule> page = reader.fetchPage(dep, arr, from, cursorId, pageSize);
        return queryProperties.pipeline()
                ? ScheduleQueryReader.toResponsesBatched(page, preemption)
                : ScheduleQueryReader.toResponses(page, preemption);
    }

    /** 한 페이지를 꽉 채웠을 때만 다음 커서를 발급한다(꽉 안 차면 마지막 페이지). */
    private static ScheduleListResponse.@Nullable Cursor nextCursor(List<ScheduleResponse> items, int pageSize) {
        if (items.size() < pageSize) {
            return null;
        }
        ScheduleResponse last = items.getLast();
        return new ScheduleListResponse.Cursor(last.departureTime(), last.scheduleId());
    }

    private static int clampLimit(@Nullable Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.clamp(limit, 1, MAX_LIMIT);
    }
}
