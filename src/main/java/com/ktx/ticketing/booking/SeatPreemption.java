package com.ktx.ticketing.booking;

import org.jspecify.annotations.Nullable;

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
}
