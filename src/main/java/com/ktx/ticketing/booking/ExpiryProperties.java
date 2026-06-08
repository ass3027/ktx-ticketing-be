package com.ktx.ticketing.booking;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HELD TTL 만료 스케줄러(T3-9) 설정.
 *
 * @param batchSize sweep 1회가 처리할 만료 대상 최대 건수. 한 번에 무한정 처리하면 긴 트랜잭션·부하 스파이크가
 *                  생기므로 상한을 둬 처리량을 bound 한다(밀린 만료는 다음 sweep 이 이어받는다).
 */
@ConfigurationProperties(prefix = "booking.expiry")
public record ExpiryProperties(
        int batchSize
) {
}
