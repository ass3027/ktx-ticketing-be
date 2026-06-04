package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.Reservation;
import lombok.RequiredArgsConstructor;
import com.ktx.ticketing.infra.DistributedLock;
import org.springframework.stereotype.Service;

/**
 * E1 비교용: 분산락으로 동시성을 제어하는 예매 서비스.
 * Redis 선점(SREM/SPOP) 없이 락 획득 → 트랜잭션(BookingTransactionHelper) 순으로 진행한다.
 * 락 라이브러리 자체는 {@link DistributedLock} 뒤로 숨겨져 있어, E6(T4-11) 실험에서 구현체만 교체하면 된다.
 */
@Service
@RequiredArgsConstructor
public class LockBookingService {

    private final DistributedLock lock;
    private final BookingTransactionHelper txHelper;

    /**
     * SEAT 모드: 스케줄 단위 락 획득 후 지정 좌석 HELD 전이.
     * @return 성공 시 {@link BookingResult.Success}, 락 미획득 또는 좌석 이미 점유 시 {@link BookingResult.SeatTaken}
     */
    public BookingResult bookSeat(Long userId, Long scheduleId, Long seatInventoryId) {
        Reservation reservation = lock.executeWithLock("schedule:" + scheduleId,
                () -> txHelper.holdSeat(userId, seatInventoryId));
        return reservation != null ? new BookingResult.Success(reservation) : new BookingResult.SeatTaken();
    }

    /**
     * AUTO 모드: 스케줄 단위 락 획득 후 AVAILABLE 좌석 1개 조회 → HELD 전이.
     * @return 성공 시 {@link BookingResult.Success}, 잔여석 없으면 {@link BookingResult.SoldOut}
     *         (락 미획득도 배정 불가로 보아 동일 처리 — 재시도 대상)
     */
    public BookingResult bookAuto(Long userId, Long scheduleId) {
        Reservation reservation = lock.executeWithLock("schedule:" + scheduleId,
                () -> txHelper.holdAnySeat(userId, scheduleId));
        return reservation != null ? new BookingResult.Success(reservation) : new BookingResult.SoldOut();
    }
}
