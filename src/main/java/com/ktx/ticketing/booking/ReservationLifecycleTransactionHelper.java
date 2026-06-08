package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.Reservation;
import com.ktx.ticketing.domain.ReservationRepository;
import com.ktx.ticketing.domain.ReservationStatus;
import com.ktx.ticketing.domain.SeatInventory;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 확정/취소의 DB 상태 전이를 트랜잭션 안에서 수행하고, 커밋 후 Redis 부수효과에 필요한 식별자를 추출해 돌려준다.
 *
 * <p>커밋 후 엔티티는 detached LAZY 라 오케스트레이터가 {@code reservation.getSeatInventory().getSchedule()}
 * 에 접근하면 {@code LazyInitializationException} 이 난다. 따라서 scheduleId/seatInventoryId 를
 * <b>트랜잭션 내에서</b> 꺼내 {@link Outcome} 에 담아 넘긴다. 부수효과는 <b>실제 전이가 일어난 경우에만</b>
 * 식별자를 채워, 오케스트레이터가 이중 취소/확정을 흡수(no-op)하도록 한다.
 */
@Service
@RequiredArgsConstructor
public class ReservationLifecycleTransactionHelper {

    private final ReservationRepository reservationRepository;
    private final Clock clock;

    /**
     * 전이 결과 + 커밋 후 부수효과 대상 식별자.
     *
     * @param leaveScheduleId 활성 슬롯을 반환(leave)할 scheduleId. {@code null} = 부수효과 없음(거절/멱등 no-op)
     * @param returnSeatId    가용 풀로 반환(SADD)할 seatInventoryId. {@code null} = 좌석 반환 없음(확정/거절)
     */
    record Outcome(ReservationCommandResult result,
                   @Nullable Long leaveScheduleId,
                   @Nullable Long returnSeatId) {

        /** 부수효과 없는 결과(거절·멱등 no-op). */
        static Outcome sideEffectFree(ReservationCommandResult result) {
            return new Outcome(result, null, null);
        }
    }

    /** 만료 전이로 풀어줄 좌석 — 커밋 후 가용 풀(SADD)·활성 슬롯(DECR) 반환 대상. */
    record ExpiredRelease(Long scheduleId, Long seatInventoryId) {}

    /** HELD → CONFIRMED + 좌석 SOLD. 확정 좌석은 가용 풀에 반환하지 않으므로 활성 슬롯만 반환 대상. */
    @Transactional
    public Outcome confirm(Long reservationId, Long userId) {
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null) {
            return Outcome.sideEffectFree(new ReservationCommandResult.NotFound());
        }
        if (!reservation.getUser().getId().equals(userId)) {
            return Outcome.sideEffectFree(new ReservationCommandResult.Forbidden()); // 신뢰 경계
        }
        if (reservation.getStatus() != ReservationStatus.HELD) {
            return Outcome.sideEffectFree(new ReservationCommandResult.IllegalState()); // 이미 확정/취소/만료
        }

        SeatInventory inventory = reservation.getSeatInventory();
        reservation.confirm(clock); // HELD → CONFIRMED
        inventory.confirm();        // HELD → SOLD (@Version 이 동시 전이의 최종 방어선)
        return new Outcome(new ReservationCommandResult.Success(reservation),
                inventory.getSchedule().getId(), null);
    }

    /** HELD/CONFIRMED → CANCELLED + 좌석 AVAILABLE. CANCELLED 재취소는 멱등 no-op, 그 외(EXPIRED)는 거절. */
    @Transactional
    public Outcome cancel(Long reservationId, Long userId) {
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null) {
            return Outcome.sideEffectFree(new ReservationCommandResult.NotFound());
        }
        if (!reservation.getUser().getId().equals(userId)) {
            return Outcome.sideEffectFree(new ReservationCommandResult.Forbidden());
        }
        ReservationStatus status = reservation.getStatus();
        if (status == ReservationStatus.CANCELLED) {
            // 이미 취소됨 — 첫 취소에서 슬롯·좌석을 이미 반환했으므로 부수효과 없이 멱등 성공
            return Outcome.sideEffectFree(new ReservationCommandResult.Success(reservation));
        }
        if (status != ReservationStatus.HELD && status != ReservationStatus.CONFIRMED) {
            return Outcome.sideEffectFree(new ReservationCommandResult.IllegalState()); // EXPIRED 등
        }

        SeatInventory inventory = reservation.getSeatInventory();
        reservation.cancel(clock); // HELD/CONFIRMED → CANCELLED
        inventory.release();       // → AVAILABLE
        return new Outcome(new ReservationCommandResult.Success(reservation),
                inventory.getSchedule().getId(), inventory.getId());
    }

    /**
     * HELD → EXPIRED + 좌석 AVAILABLE (만료 스케줄러 T3-9). 조회~전이 사이 사용자 confirm/cancel 경합을
     * <b>상태 재확인</b>으로 흡수 — HELD 가 아니면 {@code null}(no-op, 사용자가 이미 처리). 동시 전이는
     * {@code @Version} 으로 1건만 성공한다. 반환값은 커밋 후 가용 풀·활성 슬롯 반환 대상.
     */
    @Transactional
    public @Nullable ExpiredRelease expire(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null || reservation.getStatus() != ReservationStatus.HELD) {
            return null; // 사라졌거나 이미 확정/취소됨 — 만료 불필요
        }
        SeatInventory inventory = reservation.getSeatInventory();
        reservation.expire(clock); // HELD → EXPIRED
        inventory.release();       // → AVAILABLE
        return new ExpiredRelease(inventory.getSchedule().getId(), inventory.getId());
    }
}
