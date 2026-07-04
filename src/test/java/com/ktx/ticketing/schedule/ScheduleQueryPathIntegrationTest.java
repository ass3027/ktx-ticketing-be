package com.ktx.ticketing.schedule;

import com.ktx.ticketing.booking.SeatPreemption;
import com.ktx.ticketing.domain.Schedule;
import com.ktx.ticketing.domain.Seat;
import com.ktx.ticketing.domain.SeatInventory;
import com.ktx.ticketing.domain.SeatInventoryRepository;
import com.ktx.ticketing.domain.Train;
import com.ktx.ticketing.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-5 ② tx-밖 토글의 <b>정합성·안전성</b> 통합 검증(실 MySQL/Redis). 단위 테스트가 못 잡는 두 가지를 본다:
 * <ol>
 *   <li><b>lazy 안전</b>: on(SCARD tx 밖)은 조회 트랜잭션이 끝난 detached 엔티티에서 {@code getTrain()} 을
 *       접근한다. {@code findPageAfter} 가 train 을 fetch join 하므로 {@code LazyInitializationException} 이
 *       나지 않아야 한다(open-in-view=false). — mock 으론 재현 불가.</li>
 *   <li><b>결과 등가</b>: on 과 off 가 동일 입력에 동일 응답(잔여석·매진·train 정보)을 낸다. ② 는 SCARD 의
 *       <i>위치</i>만 바꿀 뿐 값·판정은 불변이어야 한다.</li>
 * </ol>
 * 각 토글은 프로퍼티 재기동 없이, 트랜잭션 reader 빈(프록시)을 그대로 주입한 서비스를 토글별로 조립해 확인한다.
 */
class ScheduleQueryPathIntegrationTest extends AbstractIntegrationTest {

    @Autowired ScheduleQueryReader reader;
    @Autowired SeatPreemption preemption;
    @Autowired SeatInventoryRepository seatInventoryRepository;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired StringRedisTemplate redis;

    private static final AtomicInteger UNIQUE = new AtomicInteger();
    private static final LocalDateTime DEPART = LocalDateTime.of(2026, 12, 1, 8, 0);

    private String dep;
    private String arr;
    private long scheduleId;
    private String trainNumber;

    @BeforeEach
    void setUp() {
        // 컨텍스트 공유 DB 에서 이 테스트만 조회되도록 고유 노선(dep/arr)으로 격리한다.
        int n = UNIQUE.incrementAndGet();
        dep = "QSEOUL-" + n;
        arr = "QBUSAN-" + n;
        trainNumber = "KTX-q-" + n;
        tx.executeWithoutResult(status -> {
            Train train = new Train("KTX 경부선", trainNumber);
            em.persist(train);
            Schedule schedule = new Schedule(train, dep, arr, DEPART, DEPART.plusHours(2), 3);
            em.persist(schedule);
            for (int i = 1; i <= 3; i++) {
                Seat seat = new Seat(train, 1, "q-" + n + "-" + i);
                em.persist(seat);
                em.persist(new SeatInventory(schedule, seat));
            }
            em.flush();
            scheduleId = schedule.getId();
        });
        // 가용 풀(Redis)을 좌석 3개로 초기화 → remainingSeats=3.
        preemption.initInventory(scheduleId, seatInventoryRepository.findAvailableIdsByScheduleId(scheduleId));
    }

    @AfterEach
    void cleanup() {
        redis.delete("avail:" + scheduleId);
        redis.delete("preempt:ts:" + scheduleId);
    }

    @Test
    @DisplayName("tx-밖(on)/tx-안(off) 토글이 동일 응답을 내고, on 은 detached 엔티티 train 접근이 안전하다")
    void 토글_on_off가_동일_응답이고_tx밖_train접근이_안전() {
        List<ScheduleResponse> off = search(false);
        List<ScheduleResponse> on = search(true);

        // on 경로는 tx 종료 후 getTrain() 접근 — 여기까지 예외 없이 왔다는 것 자체가 lazy 안전의 증거.
        assertThat(on).singleElement().satisfies(item -> {
            assertThat(item.scheduleId()).isEqualTo(scheduleId);
            assertThat(item.trainNumber()).isEqualTo(trainNumber); // detached train 이 초기화돼 있어야 값이 나온다
            assertThat(item.remainingSeats()).isEqualTo(3);
            assertThat(item.soldOut()).isFalse();
        });
        // 위치만 바뀌었을 뿐 값·판정 불변 → 두 경로 응답이 완전히 같아야 한다(record equals).
        assertThat(on).isEqualTo(off);
    }

    private List<ScheduleResponse> search(boolean redisOutsideTx) {
        var service = new ScheduleQueryService(reader, preemption, new QueryProperties(redisOutsideTx));
        return service.search(dep, arr, DEPART, null, 100).items();
    }
}
