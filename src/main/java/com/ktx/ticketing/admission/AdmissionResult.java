package com.ktx.ticketing.admission;

import java.time.Duration;

/**
 * 입장 시도 결과. 활성자 상한 K 미만이면 {@link Admitted}(토큰 발급), 초과면 {@link Rejected}(재시도 안내).
 * 초과는 정상적인 부하 셰딩이므로 예외가 아니라 값으로 표현한다.
 */
public sealed interface AdmissionResult {

    /** 입장 허용 — 발급된 EntryToken. 컨트롤러가 201 로 매핑. */
    record Admitted(EntryToken token) implements AdmissionResult {}

    /** 입장 초과(활성자 ≥ K) — 컨트롤러가 429 + Retry-After 로 매핑. */
    record Rejected(Duration retryAfter) implements AdmissionResult {}
}
