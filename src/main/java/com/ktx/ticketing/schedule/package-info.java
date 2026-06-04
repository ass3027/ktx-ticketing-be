/**
 * 운행편 조회·표시 기능(읽기 경로). 예매(쓰기) 경로인 {@code booking} 과 분리된 기능 단위 패키지.
 *
 * <p>설계상 이 경로는 <b>약한 일관성</b>이 허용된다(잔여석/매진 표시 stale ≤ ~2s). T3-1 은 DB에서
 * 운행편 리스트를 커서 페이징으로 조회하는 부분까지만 담당하고, 잔여석/매진 카운터(Redis, T3-2)와
 * 조회 캐시(T4-3, E3)는 이후 같은 패키지에 누적된다.
 */
@NullMarked
package com.ktx.ticketing.schedule;

import org.jspecify.annotations.NullMarked;
