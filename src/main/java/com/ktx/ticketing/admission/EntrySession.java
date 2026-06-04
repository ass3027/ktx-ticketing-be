package com.ktx.ticketing.admission;

/** EntryToken 이 가리키는 활성 세션의 주체 — 어느 운행편의 어느 사용자인지. 예매(T3-6)가 토큰 검증 결과로 받는다. */
public record EntrySession(Long scheduleId, Long userId) {
}
