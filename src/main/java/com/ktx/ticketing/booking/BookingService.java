package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 선점(SeatPreemption, 기본 Redis Set) 후 DB 상태전이를 수행한다.
 * 낙관적 락(@Version)이 최종 방어선 — 선점 경쟁에서 이겼어도 동시 DB 쓰기 충돌 시 예외 발생.
 */
@Service
@RequiredArgsConstructor
public class BookingService {

    private final SeatPreemption preemption;
    private final SeatInventoryRepository seatInventoryRepository;
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * SEAT 모드: 특정 좌석 직접 선택 예매.
     * @return 성공 시 {@link BookingResult.Success}, 선점 패배 시 {@link BookingResult.SeatTaken}
     */
    @Transactional
    public BookingResult bookSeat(Long userId, Long scheduleId, Long seatInventoryId) {
        if (!preemption.tryPreemptSeat(scheduleId, seatInventoryId)) {
            return new BookingResult.SeatTaken(); // 이미 다른 요청이 선점
        }
        return new BookingResult.Success(doHold(userId, seatInventoryId));
    }

    /**
     * AUTO 모드: 자동 배정 예매.
     * @return 성공 시 {@link BookingResult.Success}, 잔여석 없으면 {@link BookingResult.SoldOut}
     */
    @Transactional
    public BookingResult bookAuto(Long userId, Long scheduleId) {
        Long seatInventoryId = preemption.popAnySeat(scheduleId);
        if (seatInventoryId == null) {
            return new BookingResult.SoldOut(); // 잔여석 없음
        }
        return new BookingResult.Success(doHold(userId, seatInventoryId));
    }

    private Reservation doHold(Long userId, Long seatInventoryId) {
        User user = userRepository.getReferenceById(userId);
        SeatInventory inventory = seatInventoryRepository.findById(seatInventoryId)
                .orElseThrow(() -> new IllegalStateException("SeatInventory not found: " + seatInventoryId));

        Reservation reservation = Reservation.hold(user, inventory, clock);
        reservationRepository.save(reservation);
        return reservation;
    }
}
