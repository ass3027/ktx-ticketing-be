/**
 * 예매 핵심 경로. JSpecify {@code @NullMarked} 로 패키지 전체를 기본 non-null 로 선언한다.
 *
 * <p>공개 예매 진입점({@code BookingService}/{@code LockBookingService})은 경쟁 패배·잔여석 없음을
 * {@code null} 이 아니라 {@link com.ktx.ticketing.booking.BookingResult} 변형으로 반환한다(T3-5b).
 * 트랜잭션 경계 내부 헬퍼({@code BookingTransactionHelper})만 좌석 점유 실패를 {@code @Nullable} 로 표현하고,
 * 그 매핑은 호출 측({@code LockBookingService})이 담당한다.
 */
@NullMarked
package com.ktx.ticketing.booking;

import org.jspecify.annotations.NullMarked;
