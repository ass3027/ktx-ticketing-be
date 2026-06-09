package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.ScheduleRepository;
import com.ktx.ticketing.domain.SeatInventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Redis 가용 풀(avail Set)을 DB(SoT, {@code SeatInventory.status=AVAILABLE})로 수렴시키는 reconcile 잡(T3-10).
 * 둘은 원자적으로 갱신되지 않아 드리프트가 생긴다(예매 롤백 시 보상 SADD 없음 / 커밋 후 부수효과 실패 / 크래시).
 *
 * <p><b>방향 비대칭이 핵심</b>(설계: {@code docs/KTX_Ticketing_Reconcile_Design.md} §4·§7):
 * <ul>
 *   <li><b>stale 제거(SREM)</b> — Redis엔 있는데 DB는 AVAILABLE 아님. 최악이 언더셀(자가치유) → <b>상시 안전</b>.</li>
 *   <li><b>missing 추가(SADD)</b> — DB는 AVAILABLE인데 Redis 부재. 잘못하면 이미 잡힌 좌석을 되살려 <b>오버셀</b>
 *       → 좌석의 마지막 선점 시각이 grace 이내면 {@code [SREM~커밋]} in-flight 로 보고 <b>건너뛴다.</b></li>
 * </ul>
 * 잔여석 카운터는 {@code SCARD(avail)} 파생(T3-2)이라 avail 만 맞으면 자동 수렴한다.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final ScheduleRepository scheduleRepository;
    private final SeatInventoryRepository seatInventoryRepository;
    private final SeatPreemption preemption;
    private final ReconcileProperties properties;
    private final Clock clock;

    /** 미출발 스케줄 전체를 보정하고 합산 드리프트 리포트를 반환한다. */
    public DriftReport reconcile() {
        List<Long> scheduleIds = scheduleRepository.findUpcomingIds(LocalDateTime.now(clock));
        DriftReport total = DriftReport.empty();
        for (Long scheduleId : scheduleIds) {
            total = total.plus(reconcileSchedule(scheduleId));
        }
        return total;
    }

    /** 한 스케줄의 가용 풀을 DB 기준으로 보정한다. */
    DriftReport reconcileSchedule(Long scheduleId) {
        Set<Long> dbAvail = Set.copyOf(seatInventoryRepository.findAvailableIdsByScheduleId(scheduleId));
        Set<Long> redisAvail = preemption.availableSeatIds(scheduleId);

        long now = clock.millis();
        long graceMillis = properties.preemptGrace().toMillis();

        int staleRemoved = 0;
        int missingAdded = 0;
        int missingSkipped = 0;

        // stale: Redis有 DB無 → 풀에서 제거(SREM). DB가 HELD/SOLD 로 본 좌석이 풀에 남아있는 경우.
        for (Long seatId : redisAvail) {
            if (!dbAvail.contains(seatId)) {
                preemption.removeSeat(scheduleId, seatId);
                staleRemoved++;
            }
        }

        // missing: DB有 Redis無 → in-flight 선점이 아님이 증명될 때만 가용 풀로 되돌림(SADD).
        for (Long seatId : dbAvail) {
            if (redisAvail.contains(seatId)) {
                continue;
            }
            long preemptedAt = preemption.preemptedAtMillis(scheduleId, seatId);
            if (preemptedAt == 0L || now - preemptedAt > graceMillis) {
                preemption.returnSeat(scheduleId, seatId); // 선점 흔적 없음/오래됨 = 진짜 드리프트
                missingAdded++;
            } else {
                missingSkipped++; // 최근 선점 = [SREM~커밋] in-flight → 되살리면 오버셀
            }
        }
        return new DriftReport(staleRemoved, missingAdded, missingSkipped);
    }

    /**
     * reconcile 결과 — 관측성(Before/After 산출물)을 위한 드리프트 집계.
     *
     * @param staleRemoved   풀에서 제거한 stale 좌석 수(SREM)
     * @param missingAdded   풀로 되돌린 missing 좌석 수(SADD)
     * @param missingSkipped in-flight 로 판단해 건너뛴 missing 좌석 수(정상 — 오버셀 방지)
     */
    public record DriftReport(int staleRemoved, int missingAdded, int missingSkipped) {

        static DriftReport empty() {
            return new DriftReport(0, 0, 0);
        }

        DriftReport plus(DriftReport other) {
            return new DriftReport(
                    staleRemoved + other.staleRemoved,
                    missingAdded + other.missingAdded,
                    missingSkipped + other.missingSkipped);
        }

        /** 실제 보정(제거/추가)이 일어났는지 — 로깅 트리거. skip 은 정상 in-flight 라 보정 아님. */
        public boolean hasCorrections() {
            return staleRemoved > 0 || missingAdded > 0;
        }
    }
}
