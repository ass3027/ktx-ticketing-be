/**
 * Redis–DB reconcile 잡(T3-10). 선점에 쓰이는 {@code avail:{scheduleId}} Set 을 SoT 인
 * {@code SeatInventory.status=AVAILABLE} 로 주기적으로 수렴시킨다.
 *
 * <p>booking 컨텍스트의 일부 — 같은 invariant("oversell = 0", 좌석 상태 머신)와 같은 어휘
 * ({@code avail}, {@code HELD}, {@code preempt})를 공유하므로 별도 컨텍스트로 분리하지 않는다.
 * DDD Module 관점에서 booking 내부의 "사후 수렴" 활동을 시각적으로 분리한 하위 패키지일 뿐이다.
 * 선점 프로토콜({@link com.ktx.ticketing.booking.SeatPreemption})과 같은 축으로 함께 변경된다.
 *
 * <p>방향 비대칭(stale SREM 상시 안전 / missing SADD 위험)은 Reconcile Design 문서 §4·§7 참조.
 */
@NullMarked
package com.ktx.ticketing.booking.reconcile;

import org.jspecify.annotations.NullMarked;
