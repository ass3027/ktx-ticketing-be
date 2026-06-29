package com.ktx.ticketing.booking;

import java.util.List;
import java.util.Map;

/**
 * 만료 sweep 의 커밋 후 Redis 부수효과(좌석 반환 SADD·활성 슬롯 DECR) 발사 전략 (T4-13).
 *
 * <p>가변점은 <b>발사 방식</b>뿐이다 — 대상(실제 만료된 건만)·순서·정합성 규칙은 {@link HeldExpiryService}
 * 한 곳에 고정돼 있고, 두 구현체 어디서도 위반할 수 없다. {@code booking.expiry.batch-side-effects} 로
 * {@link BatchExpirySideEffects}(After, schedule 수로 RTT bound) / {@link PerRowExpirySideEffects}(Before,
 * 건별 직렬 2N RTT) 를 토글해 RTT 차이를 측정한다. ({@link SeatPreemption}·DistributedLock 과 동일한 SPI 패턴.)
 */
public interface ExpirySideEffects {

    /**
     * 실제 만료 전이된 좌석들의 부수효과(좌석 반환·활성 슬롯 반환)를 발사한다.
     *
     * @param seatsBySchedule scheduleId → 만료로 회수된 좌석 id 목록. 각 좌석은 정확히 1회 만료된 건뿐이다.
     */
    void release(Map<Long, List<Long>> seatsBySchedule);
}
