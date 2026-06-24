package com.ktx.ticketing.booking.reconcile;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 부하 후 정합성 audit 엔드포인트(U-2). k6 시나리오 teardown 이 호출해 자동 판정한다.
 *
 * <p>{@code GET /internal/consistency} → {@link ConsistencyReport}(availDrift·expiredHeld·statusViolation).
 * <b>읽기전용</b>(mutation 없음). 실 사용자에게 노출할 진단용 내부 경로라 {@code @Profile("!prod")} 로
 * 프로덕션에서는 빈 자체를 등록하지 않는다(부하/개발 프로파일에서만 노출).
 */
@RestController
@Profile("!prod")
@RequiredArgsConstructor
public class ConsistencyController {

    private final ConsistencyAuditService auditService;

    @GetMapping("/internal/consistency")
    public ConsistencyReport consistency() {
        return auditService.audit();
    }
}
