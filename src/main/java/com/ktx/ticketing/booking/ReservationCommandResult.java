package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.Reservation;

/**
 * 예약 생명주기 명령(확정/취소)의 결과. {@link BookingResult} 와 같은 사유로 sealed 값으로 표현해,
 * 컨트롤러가 {@code switch} 로 변형별 HTTP 를 매핑할 때 누락을 컴파일러가 잡도록 한다.
 *
 * <p>변형별 컨트롤러 매핑:
 * <ul>
 *   <li>{@link Success} → 확정 200 OK / 취소 204 No Content</li>
 *   <li>{@link NotFound} → 404 (예약 없음)</li>
 *   <li>{@link Forbidden} → 403 (토큰 세션의 사용자 ≠ 예약 소유자)</li>
 *   <li>{@link IllegalState} → 409 (전이 불가 상태: 이미 확정/만료 등)</li>
 * </ul>
 */
public sealed interface ReservationCommandResult {

    /** 성공 — 전이된(또는 멱등 no-op 인) 예약. */
    record Success(Reservation reservation) implements ReservationCommandResult {}

    /** 해당 id 의 예약이 없음. */
    record NotFound() implements ReservationCommandResult {}

    /** 토큰 세션의 사용자가 예약의 소유자가 아님 — 신뢰 경계 위반. */
    record Forbidden() implements ReservationCommandResult {}

    /** 현재 상태에서 허용되지 않는 전이(예: 이미 확정된 예약을 재확정, 만료된 예약을 취소). */
    record IllegalState() implements ReservationCommandResult {}
}
