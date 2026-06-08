package com.ktx.ticketing.booking;

import com.ktx.ticketing.admission.AdmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 예약 생명주기(확정/취소) 오케스트레이션.
 *
 * <p>DB 상태 전이는 {@link ReservationLifecycleTransactionHelper} 의 트랜잭션 안에서 수행하고,
 * Redis 부수효과(좌석 반환 SADD·활성 슬롯 반환 DECR)는 <b>반드시 커밋 이후</b>에 실행한다 —
 * 커밋 전에 좌석/슬롯을 풀면 트랜잭션 롤백 시 오버셀·과다 입장이 발생하기 때문. 헬퍼가 실제 전이가
 * 일어난 경우에만 식별자를 채워 주므로, 식별자 유무로 부수효과 실행 여부를 가려 이중 취소/확정을 흡수한다.
 *
 * <p>확정/취소는 단일 예약·단일 소유자 연산이라 분산락을 쓰지 않는다 — 동시 전이는 {@code SeatInventory}
 * 의 {@code @Version}(낙관적 락)이 최종 방어선이다. EntryToken 회수(revoke)는 토큰을 보유한 컨트롤러가 담당.
 */
@Service
@RequiredArgsConstructor
public class ReservationLifecycleService {

    private final ReservationLifecycleTransactionHelper txHelper;
    private final SeatPreemption preemption;
    private final AdmissionService admissionService;

    public ReservationCommandResult confirm(Long reservationId, Long userId) {
        ReservationLifecycleTransactionHelper.Outcome outcome = txHelper.confirm(reservationId, userId);
        if (outcome.leaveScheduleId() != null) {
            admissionService.leave(outcome.leaveScheduleId()); // 커밋 후: 활성 슬롯 반환
        }
        return outcome.result();
    }

    public ReservationCommandResult cancel(Long reservationId, Long userId) {
        ReservationLifecycleTransactionHelper.Outcome outcome = txHelper.cancel(reservationId, userId);
        if (outcome.returnSeatId() != null) {
            // 커밋 후: 좌석을 가용 풀로 반환(SADD). returnSeatId 가 있으면 leaveScheduleId 도 함께 채워져 있다.
            preemption.returnSeat(outcome.leaveScheduleId(), outcome.returnSeatId());
        }
        if (outcome.leaveScheduleId() != null) {
            admissionService.leave(outcome.leaveScheduleId()); // 커밋 후: 활성 슬롯 반환
        }
        return outcome.result();
    }
}
