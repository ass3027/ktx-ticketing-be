package com.ktx.ticketing.booking;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HELD TTL 만료 스케줄러(T3-9) 설정.
 *
 * @param batchSize        sweep 1회가 처리할 만료 대상 최대 건수. 한 번에 무한정 처리하면 긴 트랜잭션·부하 스파이크가
 *                         생기므로 상한을 둬 처리량을 bound 한다(밀린 만료는 다음 sweep 이 이어받는다).
 * @param batchSideEffects 커밋 후 Redis 부수효과(좌석 반환 SADD·활성 슬롯 DECR)를 scheduleId별로 모아 발사할지 여부.
 *                         {@code true}(현행 T4-13)=schedule 수로 RTT bound, {@code false}=건별 직렬(2N RTT, Before 비교용).
 *                         DB 전이·정합성 규칙은 양쪽 동일하며 차이는 Redis RTT 묶음 여부뿐 — E(T4-13) Before/After 토글.
 */
@ConfigurationProperties(prefix = "booking.expiry")
public record ExpiryProperties(
        int batchSize,
        boolean batchSideEffects
) {
}
