/**
 * 비가시 입장 제어(admission control). 활성 세션 수를 상한 K 로 제한해 예매 로직·DB 를 보호한다.
 *
 * <p>대기 순번을 노출하지 않는다(콘서트형 대기열과의 차이). 활성자 {@code < K} 면 EntryToken 을 발급하고
 * 활성자를 +1, {@code ≥ K} 면 429 + Retry-After 로 부하 셰딩한다. 예매(T3-6)는 이 토큰을 검증한다.
 *
 * <p>일관성은 <b>근사</b>면 충분하다(보호가 목적). 상한 K 는 L5 임계점 탐색(T4-7)에서 역산·확정하므로
 * 하드코딩하지 않고 {@code booking.admission.max-active} 설정으로 외부화한다.
 */
@NullMarked
package com.ktx.ticketing.admission;

import org.jspecify.annotations.NullMarked;
