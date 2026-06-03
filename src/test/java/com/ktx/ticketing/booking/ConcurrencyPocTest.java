package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2-5: 동시 1,000 요청 oversell=0 검증 (M2 리스크 게이트)
 *
 * 실행 전제: Docker MySQL + Redis 기동 필요
 *   docker compose up mysql redis
 *
 * E1 실험 데이터:
 *   (A) Redis 선점(SREM) + 낙관락 → 성공=1 보장
 *   (B) Redisson 분산락 → 성공=1 보장 (성능 비교용)
 */
@SpringBootTest
@ActiveProfiles("local")
class ConcurrencyPocTest {

    static final int THREAD_COUNT = 1000;
    static final Long SCHEDULE_ID = 1L;
    static final Long SEAT_INVENTORY_ID = 1L;

    @Autowired BookingService bookingService;
    @Autowired LockBookingService lockBookingService;
    @Autowired SeatPreemption preemptionService;
    @Autowired SeatInventoryRepository seatInventoryRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void resetState() {
        // DB: 대상 좌석을 AVAILABLE로 초기화
        SeatInventory inv = seatInventoryRepository.findById(SEAT_INVENTORY_ID)
                .orElseThrow(() -> new IllegalStateException("SeatInventory not found. DataInitializer가 실행됐는지 확인하세요."));
        inv.release();
        seatInventoryRepository.save(inv);

        // Redis: avail Set에 대상 좌석 1개만 세팅
        preemptionService.initInventory(SCHEDULE_ID, List.of(SEAT_INVENTORY_ID));

        // 기존 예약 삭제
        reservationRepository.deleteAll(
                reservationRepository.findAll().stream()
                        .filter(r -> r.getSeatInventory().getId().equals(SEAT_INVENTORY_ID))
                        .toList()
        );
    }

    @Test
    @DisplayName("E1(A) Redis 선점 + 낙관락: 1,000 동시요청 → 성공=1, 초과판매=0")
    void redisPreemption_동시_1000요청_oversell_0() throws InterruptedException {
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        runConcurrently(THREAD_COUNT, userId ->
                bookingService.bookSeat(userId, SCHEDULE_ID, SEAT_INVENTORY_ID),
                successCount, failCount, unexpected
        );

        assertExactlyOneWon(successCount, failCount, unexpected);
    }

    @Test
    @DisplayName("E1(B) Redisson 분산락: 1,000 동시요청 → 성공=1, 초과판매=0")
    void redissonLock_동시_1000요청_oversell_0() throws InterruptedException {
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        runConcurrently(THREAD_COUNT, userId ->
                lockBookingService.bookSeat(userId, SCHEDULE_ID, SEAT_INVENTORY_ID),
                successCount, failCount, unexpected
        );

        assertExactlyOneWon(successCount, failCount, unexpected);
    }

    /**
     * 정합성 목표 검증: oversell=0 그리고 중복 예약=0.
     * 인메모리 카운터(success/fail)와 DB(SoT: 좌석 상태·예약 행)를 각각 교차검증한다.
     */
    private void assertExactlyOneWon(AtomicInteger successCount, AtomicInteger failCount,
                                     Queue<Throwable> unexpected) {
        assertThat(unexpected)
                .as("경합 패배(낙관락/락획득 실패) 외의 예외는 없어야 한다")
                .isEmpty();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(THREAD_COUNT - 1);

        long heldOrSoldCount = seatInventoryRepository.countByScheduleIdAndStatus(SCHEDULE_ID, SeatStatus.HELD)
                + seatInventoryRepository.countByScheduleIdAndStatus(SCHEDULE_ID, SeatStatus.SOLD);
        assertThat(heldOrSoldCount)
                .as("좌석 점유는 정확히 1건 (oversell=0)")
                .isEqualTo(1);

        assertThat(reservationRepository.countBySeatInventoryId(SEAT_INVENTORY_ID))
                .as("동일 좌석 예약은 정확히 1건 (중복 예약=0)")
                .isEqualTo(1);
    }

    @FunctionalInterface
    interface BookingAction {
        Object book(Long userId);
    }

    private void runConcurrently(int threadCount, BookingAction action,
                                  AtomicInteger successCount, AtomicInteger failCount,
                                  Queue<Throwable> unexpected)
            throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final long userId = (i % 10000) + 1; // user1~user10000 순환
            futures.add(executor.submit(() -> {
                ready.countDown();
                try {
                    start.await(); // 모든 스레드 준비 후 동시 시작
                    Object result = action.book(userId);
                    if (result != null) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (OptimisticLockingFailureException | CannotAcquireLockException e) {
                    failCount.incrementAndGet(); // 경합 패배 — 정상 실패 경로
                } catch (Throwable t) {
                    unexpected.add(t);           // 경합과 무관한 진짜 이상 — 별도 수집
                    failCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }));
        }

        ready.await(); // 모든 스레드 준비 완료 대기
        start.countDown(); // 동시 출발
        boolean finished = done.await(30, TimeUnit.SECONDS);
        executor.shutdownNow(); // shutdown은 진행 중 작업을 끊지 않으므로 미완료 시 강제 종료

        // 모든 참가자가 끝나야 결과가 유효하다 — 타임아웃은 명확한 실패로 처리
        assertThat(finished)
                .as("1,000개 요청이 30초 내 모두 완료되어야 결과가 유효하다")
                .isTrue();
        assertThat(successCount.get() + failCount.get())
                .as("전수 집계 — 누락된 요청이 없어야 한다")
                .isEqualTo(threadCount);
    }
}
