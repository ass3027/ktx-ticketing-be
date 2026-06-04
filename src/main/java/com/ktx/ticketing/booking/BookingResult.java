package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.Reservation;

/**
 * 예매 시도의 결과. 경쟁 패배·잔여석 없음·입장 초과는 예외가 아니라 <b>값</b>으로 표현한다.
 *
 * <p>선점 경쟁에서의 패배는 1,000 동시 요청 중 다수가 겪는 정상 흐름의 일부이므로,
 * 예외(throw/스택트레이스)로 다루기엔 의미·비용 모두 부적합하다. sealed 로 변형을 닫아두면
 * 컨트롤러가 {@code switch} 로 사유별 HTTP(201/409/410/503)를 매핑할 때 누락을 컴파일러가 잡는다.
 *
 * <p>변형별 컨트롤러 매핑:
 * <ul>
 *   <li>{@link Success} → 201 Created</li>
 *   <li>{@link SeatTaken} → 409 Conflict (SEAT: 지정 좌석을 다른 요청이 선점)</li>
 *   <li>{@link SoldOut} → 410 Gone (AUTO: 잔여석 없음)</li>
 *   <li>{@link Overloaded} → 503 Service Unavailable + Retry-After (입장 초과; T3-3~5에서 발생)</li>
 * </ul>
 */
public sealed interface BookingResult {

    /** 예매 성공 — 좌석이 HELD 상태로 전이된 예약. */
    record Success(Reservation reservation) implements BookingResult {}

    /** SEAT 모드에서 지정 좌석을 다른 요청이 이미 선점함. */
    record SeatTaken() implements BookingResult {}

    /** AUTO 모드에서 배정할 잔여 좌석이 없음. */
    record SoldOut() implements BookingResult {}

    /**
     * 활성 세션 상한(K)을 초과해 입장이 거부됨. {@code retryAfterSeconds} 후 재시도 권장.
     * 입장 제어(T3-3~5) 도입 시 발생하며, 현재는 컨트롤러 매핑을 미리 고정하기 위해 타입만 정의한다.
     */
    record Overloaded(long retryAfterSeconds) implements BookingResult {}
}
