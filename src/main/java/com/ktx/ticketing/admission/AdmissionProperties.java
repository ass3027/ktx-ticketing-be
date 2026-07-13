package com.ktx.ticketing.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 입장 제어 설정. 상한 K({@code maxActive})는 L5b 예매경로 임계점 탐색(T4-7)으로 확정: safe_TPS≈150 ×
 * W≈0.9s × 마진0.75 ≈ 100(스케줄당). 값은 설정으로 조정한다(코드 변경 0). 전역 K(active:global) 미도입 —
 * 병목은 전역(DB pool)이나 이번 범위에선 스케줄당 유지, 전역 K 는 후속 과제(Admission_K_Calibration_Plan §4).
 *
 * @param maxActive   동시 활성 세션 상한 K(스케줄당). 이 수 미만일 때만 EntryToken 발급.
 * @param tokenTtl    EntryToken 유효 시간. 만료 시 활성 슬롯은 만료 스케줄러(T3-9)/세션 종료가 회수.
 * @param retryAfter  초과 거절 시 클라이언트에 안내할 재시도 대기(Retry-After 헤더).
 */
@ConfigurationProperties(prefix = "booking.admission")
public record AdmissionProperties(
        int maxActive,
        Duration tokenTtl,
        Duration retryAfter
) {
}
