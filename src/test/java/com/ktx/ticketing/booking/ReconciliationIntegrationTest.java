package com.ktx.ticketing.booking;

import com.ktx.ticketing.booking.ReconciliationService.DriftReport;
import com.ktx.ticketing.domain.*;
import com.ktx.ticketing.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * reconcile 가 실제 Redis/DB 에서 가용 풀을 DB(SoT)로 <b>수렴</b>시키되, in-flight 선점 좌석은
 * 되돌리지 않아 <b>오버셀을 막는지</b>를 검증한다(T3-10, 설계노트 §7 / T3-11 정합성 연계).
 *
 * <p>통제된 작은 세계를 쓴다: <b>과거 출발</b> 스케줄 + 좌석 3개. 과거 출발이라 {@code findUpcomingIds} 에
 * 안 잡혀 백그라운드 reconcile 스케줄러가 이 스케줄을 건드리지 않으므로 테스트가 결정적이다.
 * 드리프트는 실제 선점 API(tryPreemptSeat/removeSeat/returnSeat)로 주입해 현실적 시나리오를 만든다.
 */
class ReconciliationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ReconciliationService reconciliationService;
    @Autowired private SeatPreemption preemption;
    @Autowired private SeatInventoryRepository seatInventoryRepository;
    @Autowired private StringRedisTemplate redis;
    @Autowired private EntityManager em;
    @Autowired private TransactionTemplate tx;

    private static final java.util.concurrent.atomic.AtomicInteger UNIQUE =
            new java.util.concurrent.atomic.AtomicInteger();

    private long scheduleId;
    private List<Long> seatIds; // 이 스케줄의 AVAILABLE 좌석 inventory id 3개

    @BeforeEach
    void setUpControlledWorld() {
        // 과거 출발 스케줄(백그라운드 reconcile 대상에서 제외) + 좌석 3개를 AVAILABLE 로 시드.
        // 도메인 엔티티 + JPA 로 저장해 FK 정합성(seat 부모행)·@Version 등을 라이프사이클에 맡긴다.
        // train_number 는 UNIQUE 이고 @AfterEach 가 DB 를 비우지 않으므로(컨텍스트 캐시로 DB 공유) 매 메서드
        // 고유 값을 부여해 중복 INSERT 충돌을 피한다. 각 메서드는 자신만의 train/schedule/좌석 세계를 갖는다.
        String trainNo = "KTX-rec-" + UNIQUE.incrementAndGet();
        LocalDateTime past = LocalDateTime.of(2020, 1, 1, 8, 0);
        tx.executeWithoutResult(status -> {
            Train train = new Train("KTX-1", trainNo);
            em.persist(train);
            Schedule schedule = new Schedule(train, "서울", "대전", past, past.plusHours(1), 3);
            em.persist(schedule);
            for (int n = 1; n <= 3; n++) {
                Seat seat = new Seat(train, 1, "rec-" + n);
                em.persist(seat);
                em.persist(new SeatInventory(schedule, seat));
            }
            em.flush();
            scheduleId = schedule.getId();
        });
        seatIds = seatInventoryRepository.findAvailableIdsByScheduleId(scheduleId);
        assertThat(seatIds).hasSize(3);

        // 가용 풀(Redis)을 DB 와 일치하는 무드리프트 baseline 으로 초기화.
        preemption.initInventory(scheduleId, seatIds);
    }

    @AfterEach
    void cleanup() {
        redis.delete("avail:" + scheduleId);
        redis.delete("preempt:ts:" + scheduleId);
    }

    @Test
    void stale는_제거하고_genuine_missing은_풀로_되돌려_DB로_수렴한다() {
        long missingSeat = seatIds.get(0);
        long bogusStale = 999_999L; // DB 에 없는 좌석 id

        // genuine missing: 선점 흔적 없이 풀에서만 사라짐(예: 예매 롤백 보상 SADD 누락)
        preemption.removeSeat(scheduleId, missingSeat);
        // stale: DB AVAILABLE 가 아닌 좌석이 풀에 남음
        preemption.returnSeat(scheduleId, bogusStale);

        DriftReport report = reconciliationService.reconcileSchedule(scheduleId);

        // 가용 풀이 DB(SoT)의 AVAILABLE 집합과 정확히 일치 = 수렴
        assertThat(preemption.availableSeatIds(scheduleId))
                .containsExactlyInAnyOrderElementsOf(seatIds);
        assertThat(report.staleRemoved()).isEqualTo(1);
        assertThat(report.missingAdded()).isEqualTo(1);
        assertThat(report.missingSkipped()).isZero();
    }

    @Test
    void inflight_선점_좌석은_DB가_AVAILABLE여도_되돌리지_않는다_오버셀_방지() {
        long inflightSeat = seatIds.get(1);

        // in-flight: 방금 선점(SREM + 최근 ts)됐으나 DB 상태전이(HELD)는 아직 커밋 전 → DB 는 여전히 AVAILABLE.
        // 이 [SREM~커밋] 윈도우의 좌석을 reconcile 가 missing 으로 오판해 되살리면 두 명이 같은 좌석을 갖는다.
        boolean won = preemption.tryPreemptSeat(scheduleId, inflightSeat);
        assertThat(won).isTrue();

        DriftReport report = reconciliationService.reconcileSchedule(scheduleId);

        // 선점된 좌석은 풀로 되돌아오면 안 된다(오버셀 차단). 나머지 2석만 남는다.
        assertThat(preemption.availableSeatIds(scheduleId))
                .doesNotContain(inflightSeat)
                .hasSize(2);
        assertThat(report.missingSkipped()).isEqualTo(1);
        assertThat(report.missingAdded()).isZero();
        assertThat(report.staleRemoved()).isZero();
    }
}
