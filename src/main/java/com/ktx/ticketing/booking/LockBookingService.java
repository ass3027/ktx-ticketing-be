package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.Reservation;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * E1 비교용: Redisson 분산락으로 동시성을 제어하는 예매 서비스.
 * Redis 선점(SREM/SPOP) 없이 락 획득 → 트랜잭션(BookingTransactionHelper) 순으로 진행.
 * 락이 트랜잭션을 감싸는 구조 — 커밋 후 락 해제 보장.
 */
@Service
public class LockBookingService {

    static final String LOCK_PREFIX = "lock:schedule:";
    static final long WAIT_SECONDS = 5;
    static final long LEASE_SECONDS = 10;

    private final RedissonClient redisson;
    private final BookingTransactionHelper txHelper;

    public LockBookingService(RedissonClient redisson, BookingTransactionHelper txHelper) {
        this.redisson = redisson;
        this.txHelper = txHelper;
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
            return txHelper.holdSeat(userId, seatInventoryId);
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
            return txHelper.holdAnySeat(userId, scheduleId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
