package com.ktx.ticketing.booking;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 좌석 선점 백엔드 추상화. 원자적 선점 지점(현재 Redis Set)에 대한 결합을 호출 측에서 분리한다.
 *
 * <p>메서드는 Redis 자료구조가 아니라 의도(선점·반환·잔여)로 기술돼 있어, E7(T4-12) 실험에서
 * Memcached 등 다른 in-memory 스토어 구현체를 교체 투입하기 위한 SPI 역할을 겸한다.
 * (Valkey/KeyDB 등 Redis 와이어 호환 스토어는 {@link RedisSetPreemption} 을 그대로 재사용하므로
 * 별도 구현체가 필요 없다 — 엔드포인트 교체만으로 동작.)
 */
public interface SeatPreemption {

    /** SEAT 모드: 지정 좌석 선점 시도. {@code true} = 선점 승자. */
    boolean tryPreemptSeat(Long scheduleId, Long seatInventoryId);

    /** AUTO 모드: 임의의 가용 좌석 1개 선점. {@code null} = 잔여석 없음. */
    @Nullable Long popAnySeat(Long scheduleId);

    /** 취소/만료 시 좌석을 가용 풀로 반환. */
    void returnSeat(Long scheduleId, Long seatInventoryId);

    /** 스케줄의 가용 좌석 풀을 (재)초기화 — 기존 상태를 비우고 주어진 좌석들로 채운다. */
    void initInventory(Long scheduleId, Iterable<Long> seatInventoryIds);

    /** 약한 일관성 잔여 좌석 수(조회/표시용). */
    long availableCount(Long scheduleId);

    /**
     * 여러 스케줄의 잔여 좌석 수를 <b>한 번의 왕복</b>으로 집계(T4-5 ③ pipeline). 조회 리스트가 페이지
     * 편수(N)만큼 {@link #availableCount} 를 직렬 호출(=N RTT)하던 것을, 명령을 파이프라인으로 묶어
     * RTT×N → RTT×1 로 줄인다. 반환 맵은 {@code scheduleId → 잔여석}(없는 좌석은 0).
     *
     * <p>기본 구현은 단건 N회 fallback 이라 <b>RTT 절감이 없다</b>(mock/타 구현체 호환용) —
     * 실제 파이프라인 이득은 구현체 오버라이드에서 나온다({@link #preemptedAtMillisAll} 와 동일한 규약).
     */
    default Map<Long, Long> availableCounts(List<Long> scheduleIds) {
        Map<Long, Long> counts = new LinkedHashMap<>(scheduleIds.size());
        for (Long id : scheduleIds) {
            counts.put(id, availableCount(id));
        }
        return counts;
    }

    // --- T3-10 reconcile 지원 (DB(SoT)와 가용 풀 드리프트 보정) ---

    /** 현재 가용 풀에 든 좌석 id 전체(SMEMBERS). reconcile 가 DB AVAILABLE 집합과 diff 한다. */
    Set<Long> availableSeatIds(Long scheduleId);

    /** 단건 좌석을 가용 풀에서 제거(SREM). stale(Redis有 DB無) 보정 — 최악이 언더셀이라 상시 안전. */
    void removeSeat(Long scheduleId, Long seatInventoryId);

    /**
     * 좌석의 마지막 선점 시각(epoch millis). 기록이 없으면 {@code 0}.
     * reconcile 가 missing 좌석을 풀로 되돌리기(SADD) 전, 이 값이 최근이면 in-flight 선점으로 보고 건너뛴다.
     */
    long preemptedAtMillis(Long scheduleId, Long seatInventoryId);

    /**
     * 한 스케줄의 모든 좌석 → 마지막 선점 시각(epoch millis). 기록 없는 좌석은 결과 맵에 없음.
     * reconcile 의 missing 루프가 좌석마다 {@link #preemptedAtMillis} 를 N회 호출(=N RTT)하는 대신
     * HGETALL 1회로 끝내기 위한 벌크 경로(T4-3 워밍업 가속).
     *
     * <p>인터페이스 수준 fallback 은 결국 N회 HGET 이라 RTT 절감 효과가 없어 의미가 없다 —
     * 구현체별 벌크 경로를 명시적으로 강제한다.
     */
    default Map<Long, Long> preemptedAtMillisAll(Long scheduleId) {
        throw new UnsupportedOperationException(
                "preemptedAtMillisAll 은 구현체별 벌크 경로가 필요하다 — 기본 fallback 미제공");
    }

    /**
     * 여러 좌석을 한 번에 가용 풀로 반환(가변인자 SADD). reconcile missing 보정에서 N회 SADD 를
     * 1회로 줄이는 경로(T4-3 워밍업 가속). 단건 호출자는 {@link #returnSeat} 을 계속 쓴다.
     *
     * <p>기본 구현은 단건 호출 N회로 대체해 mock 기반 테스트 호환성을 유지한다.
     */
    default void returnSeats(Long scheduleId, Collection<Long> seatInventoryIds) {
        for (Long id : seatInventoryIds) {
            returnSeat(scheduleId, id);
        }
    }
}
