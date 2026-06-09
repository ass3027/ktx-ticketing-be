package com.ktx.ticketing.booking;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Redis–DB reconcile 잡(T3-10) 설정.
 *
 * @param interval     reconcile sweep 실행 주기(직전 완료~다음 시작 간격). 드리프트는 드물어 만료(30s)보다
 *                     덜 잦게 둔다.
 * @param preemptGrace missing 좌석(DB AVAILABLE인데 Redis 부재)을 가용 풀로 되돌리기(SADD) 전, 마지막 선점
 *                     시각이 이 시간 이내면 {@code [SREM~커밋]} in-flight 로 보고 건너뛴다. 최대 트랜잭션
 *                     소요보다 넉넉히 크게 둬야 정상 예매를 오버셀로 오판하지 않는다.
 */
@ConfigurationProperties(prefix = "booking.reconcile")
public record ReconcileProperties(
        Duration interval,
        Duration preemptGrace
) {
}
