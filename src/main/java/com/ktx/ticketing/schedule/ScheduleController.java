package com.ktx.ticketing.schedule;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 운행편 리스트 조회 API (T3-1). 무한 스크롤을 위한 커서 페이징.
 *
 * <p>{@code GET /api/schedules?dep=서울&arr=부산&from=2026-07-01T09:00&limit=8}
 * 다음 페이지는 응답 {@code nextCursor} 의 {@code from}/{@code afterId} 를 그대로 재전달한다.
 */
@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleQueryService scheduleQueryService;

    @GetMapping
    public ScheduleListResponse list(
            @RequestParam String dep,
            @RequestParam String arr,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @Nullable Long afterId,
            @RequestParam(required = false) @Nullable Integer limit) {
        return scheduleQueryService.search(dep, arr, from, afterId, limit);
    }
}
