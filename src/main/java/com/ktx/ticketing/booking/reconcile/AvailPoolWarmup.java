package com.ktx.ticketing.booking.reconcile;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 Redis 가용 풀(avail Set)을 DB(SoT)로 1회 eager 워밍업하는 러너.
 *
 * <p>avail 풀은 평소 {@link ReconciliationScheduler} 의 주기 sweep(기본 60s)으로만 채워진다. Redis 가 유실
 * (재시작·flush·eviction)된 직후 앱이 뜨면, 다음 sweep 까지 풀이 비어 모든 예매가 "선점할 좌석 없음"으로
 * 실패하는 공백이 생긴다. 부팅 시 {@link ReconciliationService#reconcile()} 을 1회 호출해 이 공백을 없앤다.
 *
 * <p>Redis 가 이미 DB 와 일치하면(일반적인 앱 재기동) reconcile 은 no-op 이라 비용이 사실상 0이다.
 * 부하 테스트에선 {@code Reset-Seed} 가 매 회차 FLUSHDB 하므로 이 워밍업이 k6 출발 전 풀을 보장한다.
 *
 * <p>{@link ReconciliationScheduler} 와 동일하게 {@code booking.scheduler.enabled} 로 게이팅한다 —
 * 배경 reconcile 을 끄고 수동 트리거로 결정성을 확보하는 통합 테스트에선 이 워밍업도 함께 제외된다.
 *
 * <p>{@link com.ktx.ticketing.infra.DataInitializer}(시드) 이후 실행돼야 DB AVAILABLE 좌석을 읽으므로
 * 더 낮은 우선순위({@code @Order}) 로 둔다. 워밍업 실패는 부팅을 막지 않는다 — 후속 주기 reconcile 이 보정한다.
 */
@Component
@ConditionalOnProperty(name = "booking.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@Order(AvailPoolWarmup.ORDER)
@RequiredArgsConstructor
public class AvailPoolWarmup implements ApplicationRunner {

    /** DataInitializer({@code @Order(DATA_INIT)}) 보다 뒤에 실행되도록 더 큰 값. */
    static final int ORDER = 100;

    private static final Logger log = LoggerFactory.getLogger(AvailPoolWarmup.class);

    private final ReconciliationService reconciliationService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            ReconciliationService.DriftReport report = reconciliationService.reconcile();
            log.info("부팅 워밍업: avail 풀 적재 {}건 (stale 제거 {}건, in-flight 건너뜀 {}건)",
                    report.missingAdded(), report.staleRemoved(), report.missingSkipped());
        } catch (Exception e) {
            log.warn("부팅 워밍업 실패 — 후속 reconcile 주기가 보정한다", e);
        }
    }
}
