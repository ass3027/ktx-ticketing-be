package com.ktx.ticketing.schedule;

import com.ktx.ticketing.infra.DistributedLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ScheduleListCache 단위 테스트 — single-flight(double-checked locking)의 분기를 검증한다:
 * (1) GET 히트면 락도 loader 도 안 탄다, (2) 미스→락 안 재-GET 히트면 loser 로서 loader 를 안 탄다,
 * (3) 미스→재-GET 도 미스면 winner 로서 loader 1회+SET, (4) 락 미획득(null)이면 락 없이 loader 폴백,
 * (5) 역직렬화 실패는 캐시 우회(loader)로 degrade. 실 Redis 값·잔여석 정합은 통합 테스트의 책임이라
 * 여기선 mock 으로 제어 흐름만 본다.
 */
@ExtendWith(MockitoExtension.class)
class ScheduleListCacheTest {

    private static final String KEY = "qcache:list:서울:부산:2026-07-01T09:00:0:8";
    private static final Duration TTL = Duration.ofSeconds(1);

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @Mock DistributedLock distributedLock;

    // Jackson 3 는 JavaTime(LocalDateTime ISO)을 내장 지원 — 별도 모듈 등록 불필요.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ScheduleListCache cache;

    private final ScheduleListResponse loaded = new ScheduleListResponse(
            List.of(new ScheduleResponse(1L, "KTX-001", "KTX", "서울", "부산",
                    LocalDateTime.of(2026, 7, 1, 9, 0), LocalDateTime.of(2026, 7, 1, 11, 0), 100, 42, false)),
            null);

    @BeforeEach
    void setUp() {
        // 기본 케이스는 지터 0(고정 TTL) — SET TTL 이 정확히 TTL 인지 단언 가능. 지터는 아래 별도 테스트.
        cache = new ScheduleListCache(redis, objectMapper, distributedLock,
                new QueryCacheProperties(true, TTL, 0));
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    /** DistributedLock mock 이 실제로 action 을 실행하도록(=락 획득 성공) 스텁한다. */
    @SuppressWarnings("unchecked")
    private void lockRunsAction() {
        when(distributedLock.executeWithLock(eq(KEY), any())).thenAnswer(inv ->
                ((Supplier<Object>) inv.getArgument(1)).get());
    }

    @Test
    void GET_히트면_락도_loader도_타지_않고_캐시값_반환() {
        when(valueOps.get(KEY)).thenReturn(objectMapper.writeValueAsString(loaded));
        AtomicInteger loaderCalls = new AtomicInteger();

        ScheduleListResponse result = cache.getOrLoad(KEY, countingLoader(loaderCalls));

        assertThat(result).isEqualTo(loaded);
        assertThat(loaderCalls.get()).isZero();
        verify(distributedLock, never()).executeWithLock(anyString(), any());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 미스면_락안_재GET_히트시_loser라_loader를_안_탄다() {
        // 첫 GET 미스 → 락 진입 → 재-GET 은 winner 가 채운 값이 히트. (null 을 varargs 로 오해하지 않도록 체이닝)
        when(valueOps.get(KEY)).thenReturn(null).thenReturn(objectMapper.writeValueAsString(loaded));
        lockRunsAction();
        AtomicInteger loaderCalls = new AtomicInteger();

        ScheduleListResponse result = cache.getOrLoad(KEY, countingLoader(loaderCalls));

        assertThat(result).isEqualTo(loaded);
        assertThat(loaderCalls.get()).as("loser 는 재-GET 히트라 재계산 안 함").isZero();
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 미스면_재GET도_미스시_winner라_loader1회_실행하고_SET한다() {
        when(valueOps.get(KEY)).thenReturn(null).thenReturn(null); // 첫 GET·재-GET 모두 미스
        lockRunsAction();
        AtomicInteger loaderCalls = new AtomicInteger();

        ScheduleListResponse result = cache.getOrLoad(KEY, countingLoader(loaderCalls));

        assertThat(result).isEqualTo(loaded);
        assertThat(loaderCalls.get()).isEqualTo(1);
        verify(valueOps).set(eq(KEY), anyString(), eq(TTL));
    }

    @Test
    void 락_미획득_null이면_락없이_loader_폴백하고_SET한다() {
        when(valueOps.get(KEY)).thenReturn(null);
        when(distributedLock.executeWithLock(eq(KEY), any())).thenReturn(null); // WAIT 타임아웃
        AtomicInteger loaderCalls = new AtomicInteger();

        ScheduleListResponse result = cache.getOrLoad(KEY, countingLoader(loaderCalls));

        assertThat(result).isEqualTo(loaded);
        assertThat(loaderCalls.get()).as("정합성 우선 폴백").isEqualTo(1);
        verify(valueOps).set(eq(KEY), anyString(), eq(TTL));
    }

    @Test
    void 역직렬화_실패면_캐시_우회해_loader로_degrade한다() {
        when(valueOps.get(KEY)).thenReturn("{손상된 json").thenReturn(null); // 첫 GET 손상, 재-GET 미스
        lockRunsAction();
        AtomicInteger loaderCalls = new AtomicInteger();

        ScheduleListResponse result = cache.getOrLoad(KEY, countingLoader(loaderCalls));

        assertThat(result).isEqualTo(loaded);
        assertThat(loaderCalls.get()).isEqualTo(1);
    }

    @Test
    void 지터_on이면_SET_TTL이_ttl_1pm_ratio_범위에_든다() {
        // 지터 0.2 → 만료시각을 [800ms, 1200ms] 로 흩어 다중 키 동시 만료 스파이크를 분산.
        var jittered = new ScheduleListCache(redis, objectMapper, distributedLock,
                new QueryCacheProperties(true, TTL, 0.2));
        when(valueOps.get(KEY)).thenReturn(null).thenReturn(null);
        lockRunsAction();
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        // 여러 번 채워 지터가 범위 안에서 실제로 흔들리는지(고정 아님) 본다.
        for (int i = 0; i < 20; i++) {
            jittered.getOrLoad(KEY, countingLoader(new AtomicInteger()));
        }

        verify(valueOps, times(20)).set(eq(KEY), anyString(), ttlCaptor.capture());
        assertThat(ttlCaptor.getAllValues()).allSatisfy(ttl ->
                assertThat(ttl.toMillis()).isBetween(800L, 1200L));
        // 지터가 실제로 값을 흩는지 — 20회 중 서로 다른 TTL 이 2개 이상 나와야 한다(고정이면 1개).
        assertThat(ttlCaptor.getAllValues().stream().map(Duration::toMillis).distinct().count())
                .as("지터는 TTL 을 고정하지 않고 흩어야 함").isGreaterThan(1);
    }

    private Supplier<ScheduleListResponse> countingLoader(AtomicInteger calls) {
        return () -> {
            calls.incrementAndGet();
            return loaded;
        };
    }
}
