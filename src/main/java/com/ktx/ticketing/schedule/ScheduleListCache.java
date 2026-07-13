package com.ktx.ticketing.schedule;

import com.ktx.ticketing.infra.DistributedLock;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 운행편 리스트 조회의 <b>Redis 공유 단기 캐시 + single-flight</b>(T4-5 ④ = E3).
 * 히트 시 DB 커넥션 미획득 + SCARD×N 소거 — T4-5 가 규명한 DB 풀 병목을 tx 밖 GET 1왕복으로 우회한다.
 *
 * <p>로컬(Caffeine) 아닌 Redis 공유인 이유: 멀티 인스턴스 전제(실 KTX 예매)에서 로컬 캐시는 인스턴스별로
 * 표시가 갈려 잠긴 2-tier 계약("served from Redis short-TTL cache")과 어긋난다. 공유면 staleness 창이
 * 전역 1개라 인스턴스 간 표시가 일관하다.
 *
 * <p><b>single-flight(stampede 방어) — double-checked locking:</b> 핫키 TTL 만료 순간 여러 요청이 동시에
 * 미스를 봐 일제히 DB 재계산(thundering herd)하는 걸 막는다. 기존 {@link DistributedLock} 을 재사용해:
 * 미스 → 락 획득 → <b>캐시 재-GET</b>(winner 가 이미 채웠으면 히트로 즉시 통과) → 여전히 미스면 {@code loader}
 * 1회 실행 후 SET. 만료 순간 herd 가 몰려도 실제 DB 재계산은 winner 1회, loser 는 재-GET 히트로 통과한다.
 */
@Component
@RequiredArgsConstructor
public class ScheduleListCache {

    private static final Logger log = LoggerFactory.getLogger(ScheduleListCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final DistributedLock distributedLock;
    private final QueryCacheProperties properties;

    /**
     * {@code key} 로 캐시를 조회해 히트면 그 값을, 미스면 single-flight 로 {@code loader} 결과를 반환한다.
     * {@code loader} 는 non-null(②③ 컴퓨트)을 보장하므로 반환값도 non-null.
     */
    public ScheduleListResponse getOrLoad(String key, Supplier<ScheduleListResponse> loader) {
        ScheduleListResponse hit = get(key);
        if (hit != null) {
            return hit;
        }
        // 미스 → single-flight. 락 안에서 재-GET 으로 winner/loser 를 코드 분기 없이 가른다.
        ScheduleListResponse viaLock = distributedLock.executeWithLock(key, () -> {
            ScheduleListResponse recheck = get(key);
            if (recheck != null) {
                return recheck; // loser: winner 가 이미 채움.
            }
            return computeAndPut(key, loader); // winner: DB 재계산 1회.
        });
        // 락 미획득(WAIT 타임아웃, 희귀)만 null → 정합성 우선으로 락 없이 폴백 컴퓨트.
        return (viaLock != null) ? viaLock : computeAndPut(key, loader);
    }

    private ScheduleListResponse computeAndPut(String key, Supplier<ScheduleListResponse> loader) {
        ScheduleListResponse value = loader.get();
        put(key, value);
        return value;
    }

    /** 직렬화/역직렬화 실패는 캐시 우회로 degrade — 캐시는 표시 최적화라 실패해도 정확성(loader 직접)은 불변. */
    private @Nullable ScheduleListResponse get(String key) {
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ScheduleListResponse.class);
        } catch (JacksonException e) {
            log.warn("조회 캐시 역직렬화 실패, 캐시 우회: key={}", key, e);
            return null;
        }
    }

    private void put(String key, ScheduleListResponse value) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), jitteredTtl());
        } catch (JacksonException e) {
            log.warn("조회 캐시 직렬화 실패, SET 생략: key={}", key, e);
        }
    }

    /**
     * TTL 에 지터를 적용한 실제 만료 시간. ratio=0(기본)이면 고정 ttl 그대로. ratio&gt;0 이면 {@code ttl}
     * 을 {@code [1-ratio, 1+ratio]} 배로 무작위 스케일 → 다중 키 동시 만료를 시간축에 분산(DB 파도 완화).
     */
    private Duration jitteredTtl() {
        double ratio = properties.ttlJitterRatio();
        if (ratio <= 0) {
            return properties.ttl();
        }
        double scale = 1.0 + ThreadLocalRandom.current().nextDouble(-ratio, ratio);
        return Duration.ofMillis(Math.max(1, (long) (properties.ttl().toMillis() * scale)));
    }
}
