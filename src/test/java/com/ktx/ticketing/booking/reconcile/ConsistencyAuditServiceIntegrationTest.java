package com.ktx.ticketing.booking.reconcile;

import com.ktx.ticketing.booking.SeatPreemption;
import com.ktx.ticketing.domain.*;
import com.ktx.ticketing.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U-2 정합성 audit 가 세 항목(availDrift·expiredHeld·statusViolation)을 실제 Redis/DB 에서 올바르게
 * 집계하는지 검증한다. 부하 시나리오 teardown 게이트가 이 결과로 자동 판정하므로, 인위적 불일치를
 * 주입했을 때 audit 이 그것을 잡아내고(>0) 정합 상태에선 0 을 반환하는지가 단언의 핵심이다.
 *
 * <p>{@link ReconciliationIntegrationTest} 와 동일하게 <b>과거 출발</b> 스케줄(백그라운드 미대상) +
 * 좌석 3개의 통제된 작은 세계를 쓴다.
 */
class ConsistencyAuditServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ConsistencyAuditService auditService;
    @Autowired private SeatPreemption preemption;
    @Autowired private SeatInventoryRepository seatInventoryRepository;
    @Autowired private StringRedisTemplate redis;
    @Autowired private EntityManager em;
    @Autowired private TransactionTemplate tx;
    @Autowired private Clock clock;

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    private long scheduleId;
    private List<Long> seatIds;
    private long userId;

    @BeforeEach
    void setUpControlledWorld() {
        // 미래 출발 스케줄(audit 의 auditAvailDrift 는 findUpcomingIds 로 미출발만 본다) + 좌석 3개 AVAILABLE.
        String trainNo = "KTX-aud-" + UNIQUE.incrementAndGet();
        LocalDateTime future = LocalDateTime.now(clock).plusDays(1);
        tx.executeWithoutResult(status -> {
            Train train = new Train("KTX-1", trainNo);
            em.persist(train);
            Schedule schedule = new Schedule(train, "서울", "대전", future, future.plusHours(1), 3);
            em.persist(schedule);
            for (int n = 1; n <= 3; n++) {
                Seat seat = new Seat(train, 1, "aud-" + n);
                em.persist(seat);
                em.persist(new SeatInventory(schedule, seat));
            }
            User user = new User("aud-user-" + UNIQUE.get() + "@test.com", "aud-user-" + UNIQUE.get());
            em.persist(user);
            em.flush();
            scheduleId = schedule.getId();
            userId = user.getId();
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
    void 무드리프트_baseline에서는_새_위반을_만들지_않는다() {
        // 모든 항목이 전역 집계 + 컨텍스트 공유 DB 라, 내 통제 세계를 정합(baseline)으로 둔 채 audit 을
        // 두 번 호출해 그 사이 위반이 늘지 않음을 단언한다(순서 의존 제거). 위반 주입은 전용 테스트가 한다.
        ConsistencyReport first = auditService.audit();
        ConsistencyReport second = auditService.audit();

        assertThat(second.availDrift()).isEqualTo(first.availDrift());
        assertThat(second.expiredHeld()).isEqualTo(first.expiredHeld());
        assertThat(second.statusViolation()).isEqualTo(first.statusViolation());
    }

    @Test
    void availDrift_genuine_missing_좌석을_집계한다() {
        // audit 은 미출발 스케줄 '전체'를 보므로, 컨텍스트 공유 DB 에 다른 테스트의 미래 스케줄이 누적될 수
        // 있다. 절대값 대신 주입 전후 델타로 격리 — 내 좌석 1개를 missing 시키면 정확히 +1 증가해야 한다.
        long before = auditService.audit().availDrift();

        // 선점 흔적 없이 풀에서만 사라진 좌석(예: 예매 롤백 보상 SADD 누락) = 진짜 드리프트.
        preemption.removeSeat(scheduleId, seatIds.get(0));

        assertThat(auditService.audit().availDrift()).isEqualTo(before + 1);
    }

    @Test
    void availDrift_inflight_선점은_드리프트로_세지_않는다() {
        long before = auditService.audit().availDrift();

        // 방금 선점(SREM + 최근 ts)됐으나 DB 전이 전 → grace 이내라 in-flight. 되살리면 오버셀이므로 정상.
        boolean won = preemption.tryPreemptSeat(scheduleId, seatIds.get(1));
        assertThat(won).isTrue();

        // in-flight 좌석은 풀에서 빠졌어도 missing 으로 세지 않으므로 드리프트 총량 불변.
        assertThat(auditService.audit().availDrift()).isEqualTo(before);
    }

    @Test
    void expiredHeld_만료시각이_지난_HELD를_집계한다() {
        // expiredHeld 는 전역 집계 + 컨텍스트 공유 DB 라 절대값 대신 주입 전후 델타로 격리.
        long before = auditService.audit().expiredHeld();

        // 만료시각이 과거가 되도록 과거 Clock 으로 hold → expiresAt(heldAt+5분)도 과거.
        Clock past = Clock.fixed(
                LocalDateTime.now(clock).minusHours(1).atZone(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault());
        tx.executeWithoutResult(status -> {
            User user = em.find(User.class, userId);
            SeatInventory seat = em.find(SeatInventory.class, seatIds.get(0));
            em.persist(Reservation.hold(user, seat, past));
        });

        assertThat(auditService.audit().expiredHeld()).isEqualTo(before + 1);
    }

    @Test
    void statusViolation_같은_좌석_활성1건_비활성N건은_위반이_아니다() {
        // B-2 재예매 시나리오: 좌석을 잡았다 취소(CANCELLED)한 뒤 같은 좌석을 다시 예매(HELD).
        // 같은 seat_inventory_id 에 행이 2건이지만 활성은 1건뿐 → statusViolation 은 0 이어야 한다
        // (쿼리가 HELD/CONFIRMED 만 세고 CANCELLED/EXPIRED 를 제외하는지 검증).
        long before = auditService.audit().statusViolation();

        long seat = seatIds.get(0);
        tx.executeWithoutResult(status -> {
            User user = em.find(User.class, userId);
            SeatInventory si = em.find(SeatInventory.class, seat);
            Reservation first = Reservation.hold(user, si, clock);
            first.cancel(clock);   // 좌석 release → AVAILABLE, 예약은 CANCELLED
            si.release();
            em.persist(first);
            em.persist(Reservation.hold(user, si, clock)); // 같은 좌석 재예매(HELD)
        });

        // 같은 좌석 행 2건이지만 활성은 1건뿐 → statusViolation 불변(쿼리가 CANCELLED 를 제외).
        assertThat(auditService.audit().statusViolation()).isEqualTo(before);
    }
}
