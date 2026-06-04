/**
 * 예매 핵심 경로. JSpecify {@code @NullMarked} 로 패키지 전체를 기본 non-null 로 선언하고,
 * 선점 경쟁에서 패배하거나 잔여석이 없을 때 {@code null} 을 반환하는 메서드만 {@code @Nullable} 로 표시한다.
 */
@NullMarked
package com.ktx.ticketing.booking;

import org.jspecify.annotations.NullMarked;
