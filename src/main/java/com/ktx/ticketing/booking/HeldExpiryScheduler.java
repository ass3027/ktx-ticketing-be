package com.ktx.ticketing.booking;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * HELD TTL 만료 sweep 주기 트리거 (T3-9). 주기는 {@code booking.expiry.sweep-interval} 로 외부화.
 * 만료 판정·복구 로직은 {@link HeldExpiryService} 가 담당하고, 이 컴포넌트는 타이머 위임만 한다.
 *
 * <p>분산 환경 다중 인스턴스가 동시에 sweep 해도 헬퍼의 상태 재확인 + {@code @Version} 이 이중 처리를
 * 막으므로, 분산락(ShedLock 등)은 정합성상 불필요하다(중복 작업만 낭비).
 *
 * <p>HELD TTL(5분) ≫ sweep 주기라 자동화 테스트 중 만료 대상이 생기지 않아 sweep 은 빈 작업이 된다 —
 * 스케줄링을 전역 활성화해도 테스트 결정성을 해치지 않는다.
 */
@Component
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
