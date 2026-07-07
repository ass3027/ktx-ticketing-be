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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-5 ②(tx밖)·③(pipeline) 토글의 <b>정합성·안전성</b> 통합 검증(실 MySQL/Redis). 단위 테스트가 못 잡는 것:
 * <ol>
 *   <li><b>lazy 안전</b>: tx밖 경로(②-on)는 조회 트랜잭션이 끝난 detached 엔티티에서 {@code getTrain()} 을
 *       접근한다. {@code findPageAfter} 가 train 을 fetch join 하므로 {@code LazyInitializationException} 이
 *       나지 않아야 한다(open-in-view=false). 직렬/배치(③) 매퍼 양쪽에서 안전해야 한다. — mock 으론 재현 불가.</li>
 *   <li><b>결과 등가</b>: 4조합(②×③)이 동일 입력에 동일 응답(잔여석·매진·train)을 낸다. ②는 SCARD 의
 *       <i>위치</i>, ③은 <i>묶는 방식(직렬/파이프라인)</i>만 바꿀 뿐 값·판정은 불변이어야 한다.</li>
 * </ol>
 * 각 토글은 프로퍼티 재기동 없이, 트랜잭션 reader 빈(프록시)을 그대로 주입한 서비스를 조합별로 조립해 확인한다.
 */
class ScheduleQueryPathIntegrationTest extends AbstractIntegrationTest {

    @Autowired ScheduleQueryReader reader;
    @Autowired SeatPreemption preemption;
    @Autowired ScheduleListCache listCache;
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
        redis.delete("qcache:list:" + dep + ':' + arr + ':' + DEPART + ":0:100");
    }

    @Test
    @DisplayName("②×③ 4조합이 동일 응답을 내고, tx밖·배치(pipeline) 경로도 detached train 접근이 안전하다")
    void 토글_4조합이_동일_응답이고_tx밖_배치경로_train접근이_안전() {
        // baseline(tx안·직렬)을 기준으로 나머지 3조합을 비교한다.
        List<ScheduleResponse> baseline = search(false, false);

        assertThat(baseline).singleElement().satisfies(item -> {
            assertThat(item.scheduleId()).isEqualTo(scheduleId);
            assertThat(item.trainNumber()).isEqualTo(trainNumber);
            assertThat(item.remainingSeats()).isEqualTo(3);
            assertThat(item.soldOut()).isFalse();
        });

        // ② 위치(tx안/밖) × ③ 묶는 방식(직렬/파이프라인) 어느 조합이든 값·판정 불변(record equals).
        // tx밖(②-on) 조합은 tx 종료 후 getTrain() 접근 — 예외 없이 오는 것 자체가 lazy 안전의 증거이며,
        // 그중 배치(③-on) 매퍼(toResponsesBatched)도 detached 엔티티에서 안전함을 함께 확인한다.
        assertThat(search(false, true)).as("tx안·pipeline").isEqualTo(baseline);
        assertThat(search(true, false)).as("tx밖·직렬").isEqualTo(baseline);
        assertThat(search(true, true)).as("tx밖·pipeline").isEqualTo(baseline);
    }

    @Test
    @DisplayName("④ 캐시 on 이 off 와 동일 응답을 내고, 미스 후 캐시 키가 실제로 채워진다")
    void 캐시_on이_off와_동일_응답이고_미스후_키가_채워진다() {
        // 기준 = 캐시 off(②③도 off)의 실 컴퓨트 결과.
        ScheduleListResponse expected = service(false, false, false).search(dep, arr, DEPART, null, 100);

        var cachedService = service(true, false, false); // ④-on(②③ off)
        // 1차 호출: 미스 → 컴퓨트 → SET. 응답은 off 와 등가여야 한다.
        ScheduleListResponse first = cachedService.search(dep, arr, DEPART, null, 100);
        assertThat(first).as("캐시 미스 컴퓨트 응답 = off 등가").isEqualTo(expected);

        // 미스 후 캐시 키가 실제로 채워졌는지(히트 경로가 성립하는지) 직접 확인.
        String key = "qcache:list:" + dep + ':' + arr + ':' + DEPART + ":0:100";
        assertThat(redis.hasKey(key)).as("미스 후 캐시 SET").isTrue();

        // 2차 호출: 히트 → 저장된 JSON 역직렬화. 값·매진·train 이 여전히 등가여야 한다(round-trip 정합).
        ScheduleListResponse second = cachedService.search(dep, arr, DEPART, null, 100);
        assertThat(second).as("캐시 히트 응답 = off 등가").isEqualTo(expected);
    }

    private ScheduleQueryService service(boolean cacheEnabled, boolean redisOutsideTx, boolean pipeline) {
        return new ScheduleQueryService(reader, preemption, new QueryProperties(redisOutsideTx, pipeline),
                new QueryCacheProperties(cacheEnabled, Duration.ofSeconds(2), 0), listCache);
    }

    private List<ScheduleResponse> search(boolean redisOutsideTx, boolean pipeline) {
        // ④ 캐시 disabled — 이 조합 등가 테스트의 관심은 ②③ 라우팅이다(캐시 on≡off 는 위 별도 테스트).
        return service(false, redisOutsideTx, pipeline).search(dep, arr, DEPART, null, 100).items();
    }
}
