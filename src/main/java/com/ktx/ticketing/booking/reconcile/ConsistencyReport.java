package com.ktx.ticketing.booking.reconcile;

/**
 * 부하 후 정합성 audit 결과(U-2). 세 항목 모두 0 이어야 정합 — k6 teardown 게이트가
 * {@link #totalViolations()} 를 {@code consistency_violation} Counter 로 받아 {@code count==0} 으로 판정한다.
 *
 * @param availDrift      Redis 가용 풀 ↔ DB(SoT) AVAILABLE 드리프트 건수(stale 제거 + missing 추가 대상).
 *                        in-flight 선점(grace 이내)은 정상이라 제외된다.
 * @param expiredHeld     만료시각이 지났는데 HELD 로 남은 예약 수(스케줄러 미처리 잔재).
 * @param statusViolation 같은 좌석에 활성(HELD/CONFIRMED) 예약이 2건 이상인 좌석 수(오버셀/중복).
 */
public record ConsistencyReport(long availDrift, long expiredHeld, long statusViolation) {

    /** 세 항목 합산 — 0 이면 정합. teardown 게이트의 단일 판정값. */
    public long totalViolations() {
        return availDrift + expiredHeld + statusViolation;
    }
}
