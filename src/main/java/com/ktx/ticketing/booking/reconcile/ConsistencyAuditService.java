package com.ktx.ticketing.booking.reconcile;

import com.ktx.ticketing.domain.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 부하 후 정합성을 <b>읽기전용</b>으로 판정하는 audit(U-2). 기존 ps1 사후 SQL(수동)을 앱 내부 엔드포인트로
 * 병합해, k6 시나리오 teardown 이 직접 호출·자동 판정할 수 있게 한다.
 *
 * <p>세 항목을 합산해 {@link ConsistencyReport} 로 반환한다 — avail 드리프트({@link ReconciliationService}
 * 의 보정 없는 diff 재사용), 만료 미처리 HELD, 좌석당 활성 2건 이상(오버셀/중복). <b>mutation 없음</b>:
 * audit 가 상태를 바꾸면 판정 대상 자체가 오염되므로 reconcile 의 SREM/SADD 경로를 타지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ConsistencyAuditService {

    private final ReconciliationService reconciliationService;
    private final ReservationRepository reservationRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ConsistencyReport audit() {
        long availDrift = reconciliationService.auditAvailDrift().total();
        long expiredHeld = reservationRepository.countExpiredHeld(LocalDateTime.now(clock));
        long statusViolation = reservationRepository.countSeatsWithMultipleActive();
        return new ConsistencyReport(availDrift, expiredHeld, statusViolation);
    }
}
