package com.ktx.ticketing.schedule;

import com.ktx.ticketing.domain.Schedule;
import com.ktx.ticketing.domain.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 운행편 리스트 조회(읽기 경로). 커서 페이징 경계 — limit 클램프, afterId 정규화,
 * nextCursor 계산 — 을 담당하고 실제 조회는 {@link ScheduleRepository} 에 위임한다.
 */
@Service
@RequiredArgsConstructor
public class ScheduleQueryService {

    static final int DEFAULT_LIMIT = 8;
    static final int MAX_LIMIT = 100;
    /** 첫 페이지(afterId 미지정)를 departureTime >= from 으로 만들기 위한 하한 정규화 값. */
    static final long FIRST_PAGE_AFTER_ID = 0L;

    private final ScheduleRepository scheduleRepository;

    @Transactional(readOnly = true)
    public ScheduleListResponse search(String dep, String arr, LocalDateTime from,
                                       @Nullable Long afterId, @Nullable Integer limit) {
        int pageSize = clampLimit(limit);
        long cursorId = (afterId != null) ? afterId : FIRST_PAGE_AFTER_ID;

        List<Schedule> page = scheduleRepository.findPageAfter(
                dep, arr, from, cursorId, PageRequest.of(0, pageSize));

        List<ScheduleResponse> items = page.stream().map(ScheduleResponse::from).toList();
        return new ScheduleListResponse(items, nextCursor(page, pageSize));
    }

    /** 한 페이지를 꽉 채웠을 때만 다음 커서를 발급한다(꽉 안 차면 마지막 페이지). */
    private static ScheduleListResponse.@Nullable Cursor nextCursor(List<Schedule> page, int pageSize) {
        if (page.size() < pageSize) {
            return null;
        }
        Schedule last = page.getLast();
        return new ScheduleListResponse.Cursor(last.getDepartureTime(), last.getId());
    }

    private static int clampLimit(@Nullable Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.clamp(limit, 1, MAX_LIMIT);
    }
}
