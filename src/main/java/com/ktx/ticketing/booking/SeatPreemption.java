package com.ktx.ticketing.booking;

import org.jspecify.annotations.Nullable;

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
}
