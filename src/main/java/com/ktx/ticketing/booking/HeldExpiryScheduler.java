package com.ktx.ticketing.booking;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * HELD TTL 만료 sweep 주기 트리거 (T3-9). 주기는 {@code booking.expiry.sweep-interval} 로 외부화.
 * 만료 판정·복구 로직은 {@link HeldExpiryService} 가 담당하고, 이 컴포넌트는 타이머 위임만 한다.
 *
 * <p>분산 환경 다중 인스턴스가 동시에 sweep 해도 헬퍼의 상태 재확인 + {@code @Version} 이 이중 처리를
 * 막으므로, 분산락(ShedLock 등)은 정합성상 불필요하다(중복 작업만 낭비).
 *
 * <p>{@code booking.scheduler.enabled=false} 로 배경 sweep 을 끌 수 있다(기본 on, 운영 무영향).
 * 통합 테스트는 이를 끄고 {@link HeldExpiryService#sweep()} 을 수동 트리거해 만료 시점을 결정적으로 제어한다(T3-11).
 */
@Component
@ConditionalOnProperty(name = "booking.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class HeldExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeldExpiryScheduler.class);

    private final HeldExpiryService expiryService;

    @Scheduled(fixedDelayString = "${booking.expiry.sweep-interval}")
    public void sweep() {
        int expired = expiryService.sweep();
        if (expired > 0) {
            log.info("HELD 만료 복구: {}건", expired);
        }
    }
}
