package com.ktx.ticketing.booking;

import com.ktx.ticketing.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-5 ③ pipeline — {@link RedisSetPreemption#availableCounts} 배치 집계를 실제 Redis 로 검증한다.
 * 파이프라인은 executePipelined 콜백/결과정렬/역직렬화가 얽혀 mock 충실도가 낮으므로(선점 Lua 와 동일 이유)
 * Testcontainers 로 계약을 고정한다: <b>scheduleId 순서 정렬·정확한 잔여수·미존재 키 0·빈 입력 빈 맵</b>.
 * 이 등가성이 있어야 조회 리스트가 직렬 SCARD 를 pipeline 으로 바꿔도 잔여석/매진 판정이 불변이다.
 */
class RedisSetPreemptionPipelineTest extends AbstractIntegrationTest {

    private static final long SID_A = 999_101L; // 좌석 3
    private static final long SID_B = 999_102L; // 좌석 1
    private static final long SID_C = 999_103L; // 좌석 0(비움) → 매진

    @Autowired
    private RedisSetPreemption preemption;
    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void cleanup() {
        for (long sid : new long[]{SID_A, SID_B, SID_C}) {
            redis.delete("avail:" + sid);
            redis.delete("preempt:ts:" + sid);
        }
    }

    @Test
    void availableCounts_여러_스케줄_잔여석을_요청_순서대로_집계() {
        preemption.initInventory(SID_A, List.of(1L, 2L, 3L));
        preemption.initInventory(SID_B, List.of(10L));
        preemption.initInventory(SID_C, List.of());

        Map<Long, Long> counts = preemption.availableCounts(List.of(SID_A, SID_B, SID_C));

        // 파이프라인 결과가 큐잉 순서와 정렬돼 각 scheduleId 에 올바른 SCARD 값이 매핑돼야 한다.
        assertThat(counts).containsExactly(
                Map.entry(SID_A, 3L),
                Map.entry(SID_B, 1L),
                Map.entry(SID_C, 0L)); // 빈 Set = 미존재 키 → SCARD 0(매진)
    }

    @Test
    void availableCounts_배치_결과가_단건_availableCount_반복과_동일() {
        preemption.initInventory(SID_A, List.of(1L, 2L, 3L));
        preemption.initInventory(SID_B, List.of(10L));

        Map<Long, Long> batched = preemption.availableCounts(List.of(SID_A, SID_B));

        // ③ 의 불변식: pipeline(1왕복) 값이 직렬 availableCount(N왕복) 값과 완전히 같아야 한다.
        assertThat(batched.get(SID_A)).isEqualTo(preemption.availableCount(SID_A));
        assertThat(batched.get(SID_B)).isEqualTo(preemption.availableCount(SID_B));
    }

    @Test
    void availableCounts_빈_리스트면_Redis_왕복_없이_빈_맵() {
        assertThat(preemption.availableCounts(List.of())).isEmpty();
    }
}
