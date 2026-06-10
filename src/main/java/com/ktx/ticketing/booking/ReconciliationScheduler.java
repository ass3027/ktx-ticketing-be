package com.ktx.ticketing.booking;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis–DB reconcile 주기 트리거(T3-10). 주기는 {@code booking.reconcile.interval} 로 외부화.
 * diff·보정 로직은 {@link ReconciliationService} 가 담당하고, 이 컴포넌트는 타이머 위임·드리프트 로깅만 한다.
 *
 * <p>다중 인스턴스가 동시에 reconcile 해도 정합성은 보존된다(보정 연산 멱등 + 선점 시각 마커가 in-flight 좌석의
 * SADD 를 막아 오버셀 차단). 락은 불필요하며 중복 작업만 낭비된다 — {@link HeldExpiryScheduler} 와 동일 서술.
 *
 * <p>{@code booking.scheduler.enabled=false} 로 배경 reconcile 을 끌 수 있다(기본 on, 운영 무영향).
 * 통합 테스트는 이를 끄고 {@link ReconciliationService#reconcile()} 을 수동 트리거해 결정성을 확보한다(T3-11).
 */
@Component
@ConditionalOnProperty(name = "booking.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliationService;

    @Scheduled(fixedDelayString = "${booking.reconcile.interval}")
    public void reconcile() {
        ReconciliationService.DriftReport report = reconciliationService.reconcile();
        if (report.hasCorrections()) {
            log.info("Redis-DB 드리프트 보정: stale 제거 {}건, missing 추가 {}건, in-flight 건너뜀 {}건",
                    report.staleRemoved(), report.missingAdded(), report.missingSkipped());
        }
    }
}
