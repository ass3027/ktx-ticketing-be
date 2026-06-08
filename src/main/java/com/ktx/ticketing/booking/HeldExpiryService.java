package com.ktx.ticketing.booking;

import com.ktx.ticketing.admission.AdmissionService;
import com.ktx.ticketing.booking.ReservationLifecycleTransactionHelper.ExpiredRelease;
import com.ktx.ticketing.domain.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * HELD TTL 만료 sweep 오케스트레이터 (T3-9). 만료 대상을 조회해 건별로 복구한다 —
 * 예약 HELD→EXPIRED, 좌석 HELD→AVAILABLE, 가용 풀 반환(SADD), 활성 슬롯 반환(DECR).
 *
 * <p>각 건은 <b>독립 트랜잭션</b>(헬퍼)으로 전이하고, Redis 부수효과는 <b>커밋 이후·실제 만료된 경우에만</b>
 * 수행한다 — 취소(T3-8)와 동일한 정합성 규칙. 건별 트랜잭션이라 한 건 실패가 배치 전체를 막지 않는다.
 * 조회~전이 사이 사용자 confirm/cancel 경합은 헬퍼의 상태 재확인이 흡수하므로(no-op),
 * 분산 환경 다중 스위퍼도 정합성이 보존된다(중복 작업만 낭비, 락 불필요).
 */
@Service
@RequiredArgsConstructor
public class HeldExpiryService {

    private final ReservationRepository reservationRepository;
    private final ReservationLifecycleTransactionHelper txHelper;
    private final SeatPreemption preemption;
    private final AdmissionService admissionService;
    private final ExpiryProperties properties;
    private final Clock clock;

    /**
     * 만료 대상을 batchSize 만큼 조회해 복구하고, 실제 만료 처리된 건수를 반환한다.
     * @return 이번 sweep 에서 만료→복구된 예약 수(경합으로 건너뛴 건 제외)
     */
    public int sweep() {
        List<Long> expiredIds = reservationRepository.findExpiredHeldIds(
                LocalDateTime.now(clock), PageRequest.of(0, properties.batchSize()));

        int expired = 0;
        for (Long reservationId : expiredIds) {
            ExpiredRelease released = txHelper.expire(reservationId); // 트랜잭션 + 커밋
            if (released != null) {                                   // 실제 만료된 경우에만 부수효과
                preemption.returnSeat(released.scheduleId(), released.seatInventoryId()); // SADD
                admissionService.leave(released.scheduleId());                            // DECR
                expired++;
            }
        }
        return expired;
    }
}
