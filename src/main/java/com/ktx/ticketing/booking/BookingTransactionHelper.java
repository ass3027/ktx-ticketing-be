package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 분산락 외부에서 트랜잭션을 시작해야 하므로 별도 빈으로 분리.
 * 락(LockBookingService) → 트랜잭션(이 클래스) 순서를 보장.
 */
@Service
public class BookingTransactionHelper {

    private final SeatInventoryRepository seatInventoryRepository;
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;

    public BookingTransactionHelper(SeatInventoryRepository seatInventoryRepository,
                                    ReservationRepository reservationRepository,
                                    UserRepository userRepository) {
        this.seatInventoryRepository = seatInventoryRepository;
        this.reservationRepository = reservationRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Reservation holdSeat(Long userId, Long seatInventoryId) {
        SeatInventory inventory = seatInventoryRepository.findById(seatInventoryId)
                .orElseThrow(() -> new IllegalStateException("SeatInventory not found: " + seatInventoryId));

        if (inventory.getStatus() != SeatStatus.AVAILABLE) {
            return null;
        }

        User user = userRepository.getReferenceById(userId);
        inventory.hold(LocalDateTime.now().plusMinutes(BookingService.HELD_TTL_MINUTES));
        Reservation reservation = Reservation.hold(user, inventory, BookingService.HELD_TTL_MINUTES);
        reservationRepository.save(reservation);
        return reservation;
    }

    @Transactional
    public Reservation holdAnySeat(Long userId, Long scheduleId) {
        List<SeatInventory> available = seatInventoryRepository.findAvailableByScheduleId(scheduleId);
        if (available.isEmpty()) {
            return null;
        }

        User user = userRepository.getReferenceById(userId);
        SeatInventory inventory = available.get(0);
        inventory.hold(LocalDateTime.now().plusMinutes(BookingService.HELD_TTL_MINUTES));
        Reservation reservation = Reservation.hold(user, inventory, BookingService.HELD_TTL_MINUTES);
        reservationRepository.save(reservation);
        return reservation;
    }
}
