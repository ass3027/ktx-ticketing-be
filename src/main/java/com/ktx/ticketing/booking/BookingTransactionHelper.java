package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.*;
import org.jspecify.annotations.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * 분산락 외부에서 트랜잭션을 시작해야 하므로 별도 빈으로 분리.
 * 락(LockBookingService) → 트랜잭션(이 클래스) 순서를 보장.
 */
@Service
@RequiredArgsConstructor
public class BookingTransactionHelper {

    private final SeatInventoryRepository seatInventoryRepository;
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Transactional
    public @Nullable Reservation holdSeat(Long userId, Long seatInventoryId) {
        SeatInventory inventory = seatInventoryRepository.findById(seatInventoryId)
                .orElseThrow(() -> new IllegalStateException("SeatInventory not found: " + seatInventoryId));

        if (inventory.getStatus() != SeatStatus.AVAILABLE) {
            return null;
        }

        User user = userRepository.getReferenceById(userId);
        Reservation reservation = Reservation.hold(user, inventory, clock);
        reservationRepository.save(reservation);
        return reservation;
    }

    @Transactional
    public @Nullable Reservation holdAnySeat(Long userId, Long scheduleId) {
        List<SeatInventory> available = seatInventoryRepository.findAvailableByScheduleId(scheduleId);
        if (available.isEmpty()) {
            return null;
        }

        User user = userRepository.getReferenceById(userId);
        SeatInventory inventory = available.getFirst();
        Reservation reservation = Reservation.hold(user, inventory, clock);
        reservationRepository.save(reservation);
        return reservation;
    }
}
