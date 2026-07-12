package com.ktx.ticketing.booking;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 좌석 선점(Redis SREM) 게이트 토글(T4-9 E1).
 *
 * @param enabled {@code true}(기본/프로덕션·After)=SEAT 예매 시 {@link SeatPreemption#tryPreemptSeat}로
 *                Redis 앞단에서 경합을 걸러낸다. {@code false}(E1 Before)=SREM 을 건너뛰어 모든 요청이 DB 로
 *                내려가 {@code @Version} 낙관락 하나로만 직렬화된다 — 정확성(오버셀 0)은 유지되나 처리량/지연/DB
 *                pool 점유 비용이 드러난다. "경합을 어디서 해소하나(Redis vs DB)"의 비용 대조용
 *                ({@code docs/plans/e1-e2-*.md}). AUTO(SPOP)는 선점 없이 좌석 선택이 불가라 이 토글의 대상이 아니다.
 */
@ConfigurationProperties(prefix = "booking.preemption")
public record PreemptionProperties(boolean enabled) {
}
