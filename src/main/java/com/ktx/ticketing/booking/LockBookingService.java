package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.*;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * E1 비교용: Redisson 분산락으로 동시성을 제어하는 예매 서비스.
 * Redis 선점(SREM/SPOP) 없이 락 획득 → DB 조회 → 상태전이 순서로 진행.
 * BookingService(Redis 선점 + 낙관락)와 성능/정합성 비교 실험(E1)에 사용.
 */
@Service
public class LockBookingService {

    static final String LOCK_PREFIX = "lock:schedule:";
    static final long WAIT_SECONDS = 5;
    static final long LEASE_SECONDS = 10;

    private final RedissonClient redisson;
    private final SeatInventoryRepository seatInventoryRepository;
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;

    public LockBookingService(RedissonClient redisson,
                               SeatInventoryRepository seatInventoryRepository,
                               ReservationRepository reservationRepository,
                               UserRepository userRepository) {
        this.redisson = redisson;
        this.seatInventoryRepository = seatInventoryRepository;
        this.reservationRepository = reservationRepository;
        this.userRepository = userRepository;
    }

    /**
     * SEAT 모드: 분산락 획득 후 지정 좌석 HELD 전이.
     * @return Reservation, 락 획득 실패 또는 좌석 이미 점유 시 null
     */
    public Reservation bookSeat(Long userId, Long scheduleId, Long seatInventoryId) {
        RLock lock = redisson.getLock(LOCK_PREFIX + scheduleId);
        try {
            if (!lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS)) {
                return null;
            }
            return doBookSeat(userId, seatInventoryId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * AUTO 모드: 분산락 획득 후 AVAILABLE 좌석 1개 조회 → HELD 전이.
     * @return Reservation, 락 획득 실패 또는 잔여석 없으면 null
     */
    public Reservation bookAuto(Long userId, Long scheduleId) {
        RLock lock = redisson.getLock(LOCK_PREFIX + scheduleId);
        try {
            if (!lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS)) {
                return null;
            }
            return doBookAuto(userId, scheduleId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Transactional
    protected Reservation doBookSeat(Long userId, Long seatInventoryId) {
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
    protected Reservation doBookAuto(Long userId, Long scheduleId) {
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
