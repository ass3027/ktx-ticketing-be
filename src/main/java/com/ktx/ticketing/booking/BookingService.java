package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redis 선점(SeatPreemptionService) 후 DB 상태전이를 수행한다.
 * 낙관적 락(@Version)이 최종 방어선 — 선점 경쟁에서 이겼어도 동시 DB 쓰기 충돌 시 예외 발생.
 */
@Service
@RequiredArgsConstructor
public class BookingService {

    private final SeatPreemptionService preemption;
    private final SeatInventoryRepository seatInventoryRepository;
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;

    /**
     * SEAT 모드: 특정 좌석 직접 선택 예매.
     * @return 생성된 Reservation, 선점 실패 시 null
     */
    @Transactional
    public Reservation bookSeat(Long userId, Long scheduleId, Long seatInventoryId) {
        if (!preemption.tryPreemptSeat(scheduleId, seatInventoryId)) {
            return null; // 이미 다른 요청이 선점
        }
        return doHold(userId, seatInventoryId);
    }

    /**
     * AUTO 모드: 자동 배정 예매.
     * @return 생성된 Reservation, 잔여석 없으면 null
     */
    @Transactional
    public Reservation bookAuto(Long userId, Long scheduleId) {
        Long seatInventoryId = preemption.popAnySeat(scheduleId);
        if (seatInventoryId == null) {
            return null; // 잔여석 없음
        }
        return doHold(userId, seatInventoryId);
    }

    private Reservation doHold(Long userId, Long seatInventoryId) {
        User user = userRepository.getReferenceById(userId);
        SeatInventory inventory = seatInventoryRepository.findById(seatInventoryId)
                .orElseThrow(() -> new IllegalStateException("SeatInventory not found: " + seatInventoryId));

        inventory.hold();
        Reservation reservation = Reservation.hold(user, inventory);
        reservationRepository.save(reservation);
        return reservation;
    }
}
