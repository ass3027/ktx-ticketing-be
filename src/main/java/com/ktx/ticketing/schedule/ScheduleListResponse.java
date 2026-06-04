package com.ktx.ticketing.schedule;

import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 운행편 리스트 조회 응답(커서 페이징).
 *
 * @param items      이번 페이지의 운행편들 (출발시각 오름차순, 동시각은 id 오름차순)
 * @param nextCursor 다음 페이지 커서. 더 가져올 게 없으면 {@code null}.
 *                   {@code items.size() == limit} 일 때만 채워진다.
 */
public record ScheduleListResponse(
        List<ScheduleResponse> items,
        @Nullable Cursor nextCursor
) {
    /**
     * 다음 페이지 시작점. 클라이언트는 이 값을 다음 요청의 {@code from}/{@code afterId} 로 그대로 전달한다.
     * {@code departureTime} 동률을 안정적으로 넘기기 위해 {@code afterId}(마지막 항목 id)를 함께 둔다.
     */
    public record Cursor(LocalDateTime from, Long afterId) {}
}
