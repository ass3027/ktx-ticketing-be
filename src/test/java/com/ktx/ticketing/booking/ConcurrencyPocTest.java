package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.*;
import com.ktx.ticketing.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2-5: 동시 1,000 요청 oversell=0 검증 (M2 리스크 게이트)
 *
 * 인프라(MySQL/Redis)는 {@link AbstractIntegrationTest} 의 Testcontainers 가 자동 기동한다.
 * 시드 데이터는 이 테스트가 {@code @BeforeEach} 에서 도메인 엔티티 + JPA 로 저장한다(train/schedule/seat/
 * seat_inventory 각 1건 + user 1..10000). {@code @Profile("local")} 의 DataInitializer(50k 좌석)는 test
 * 프로파일에서 안 돌고, 또 이 정합성 테스트엔 좌석 1개만 필요하므로 자급자족한다.
 *
 * E1 실험 데이터:
 *   (A) Redis 선점(SREM) + 낙관락 → 성공=1 보장
 *   (B) Redisson 분산락 → 성공=1 보장 (성능 비교용)
 */
class ConcurrencyPocTest extends AbstractIntegrationTest {

    static final int THREAD_COUNT = 1000;
    /** userId 순환 범위(runConcurrently 의 (i % USER_COUNT)+1)와 일치 — 이 수만큼 user 행을 시드한다. */
    static final int USER_COUNT = 10_000;
    /** 이 테스트 전용 train_number — 컨텍스트 공유 DB 에서 자기 시드만 식별하기 위한 격리 키. */
    static final String POC_TRAIN_NO = "KTX-poc-001";

    @Autowired BookingService bookingService;
    @Autowired LockBookingService lockBookingService;
    @Autowired SeatPreemption preemptionService;
    @Autowired SeatInventoryRepository seatInventoryRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    // 시드된 엔티티의 실제 id(@GeneratedValue 라 persist 후 채워짐). booking 호출·단언이 참조한다.
    Long scheduleId;
    Long seatInventoryId;

    @BeforeEach
    void resetState() {
        seedOnce();

        // DB: 대상 좌석을 AVAILABLE로 초기화. release() 는 "이미 AVAILABLE 재반환 금지" 불변식(오버셀 방지)을
        // 강제하므로 직전 테스트가 HELD/SOLD로 남긴 경우에만 호출한다. 시드 직후(첫 메서드)는 이미 AVAILABLE 이라 건너뜀.
        tx.executeWithoutResult(status -> {
            SeatInventory inv = seatInventoryRepository.findById(seatInventoryId)
                    .orElseThrow(() -> new IllegalStateException("SeatInventory 시드 실패 — seedOnce() 확인."));
            if (inv.getStatus() != SeatStatus.AVAILABLE) {
                inv.release();
            }
            reservationRepository.deleteAll(
                    reservationRepository.findAll().stream()
                            .filter(r -> r.getSeatInventory().getId().equals(seatInventoryId))
                            .toList()
            );
        });

        preemptionService.initInventory(scheduleId, List.of(seatInventoryId));
    }

    /**
     * 정합성 검증에 필요한 최소 시드를 <b>컨텍스트(=DB)당 1회</b> 저장하고, 매 호출마다 {@code scheduleId}/
     * {@code seatInventoryId} 필드를 DB 의 실제 값으로 채운다.
     *
     * <p>멱등 판단을 인스턴스 필드가 아니라 <b>DB 존재 여부</b>로 하는 이유: JUnit 은 테스트 메서드마다 클래스
     * 인스턴스를 새로 만들어 인스턴스 필드가 null 로 리셋되지만, {@code create-drop} DB 와 컨텍스트 캐시는 클래스
     * 전체에서 1세트만 유지된다. 따라서 "이미 시드됨"은 DB 로 판정해야 두 번째 메서드의 중복 INSERT(UNIQUE 충돌)를 막는다.
     * 판정은 <b>이 테스트 고유 train_number</b>({@value POC_TRAIN_NO})로 한정한다 — 컨텍스트 캐시를 공유하는 다른
     * 통합 테스트(BookingIntegrationTest 등)가 만든 SeatInventory 를 자기 것으로 오인하면 user 시드를 건너뛰어
     * FK 위반·엉뚱한 scheduleId 집계(oversell 오판)가 나기 때문이다.
     *
     * <p>구성: train/schedule/seat/seat_inventory 각 1건 + user 1..{@value USER_COUNT}. id 는 {@code @GeneratedValue}
     * 에 맡겨 실제 값을 필드에 담는다 — 하드코딩 id 가 다른 통합 테스트의 시드와 충돌하지 않게 한다.
     *
     * <p>JdbcTemplate 직접 INSERT 가 아니라 <b>도메인 엔티티 + JPA</b>로 저장하는 이유: {@code @CreationTimestamp}
     * (User.createdAt)·FK 정합성·{@code @Version} 등 엔티티 라이프사이클이 보장하는 불변식을 손으로 재현하지 않기 위함.
     * 시드가 도메인 모델과 한 소스라 모델 변경 시 컴파일러가 깨짐을 잡는다.
     *
     * <p>user 가 {@code USER_COUNT} 만큼 필요한 이유: 어느 userId 가 선점 경쟁에서 이길지 비결정적인데, 승자의
     * {@code Reservation.user_id} FK 가 실제 user 행을 참조해야 하기 때문이다(나머지는 선점 패배라 INSERT 없음).
     */
    private void seedOnce() {
        tx.executeWithoutResult(status -> {
            SeatInventory inv = em.createQuery(
                            "SELECT si FROM SeatInventory si WHERE si.schedule.train.trainNumber = :no",
                            SeatInventory.class)
                    .setParameter("no", POC_TRAIN_NO)
                    .getResultStream().findFirst().orElseGet(() -> {
                Train train = new Train("KTX-1", POC_TRAIN_NO);
                em.persist(train);
                Schedule schedule = new Schedule(train, "서울", "부산",
                        LocalDateTime.of(2026, 12, 1, 8, 0), LocalDateTime.of(2026, 12, 1, 10, 30), 1);
                em.persist(schedule);
                Seat seat = new Seat(train, 1, "1A");
                em.persist(seat);
                SeatInventory seeded = new SeatInventory(schedule, seat);
                em.persist(seeded);

                IntStream.rangeClosed(1, USER_COUNT)
                        .forEach(i -> em.persist(new User("user" + i + "@ktx.test", "user" + i)));

                em.flush();
                return seeded;
            });
            scheduleId = inv.getSchedule().getId();
            seatInventoryId = inv.getId();
        });
    }

    @Test
    @DisplayName("E1(A) Redis 선점 + 낙관락: 1,000 동시요청 → 성공=1, 초과판매=0")
    void redisPreemption_동시_1000요청_oversell_0() throws InterruptedException {
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        runConcurrently(THREAD_COUNT, userId ->
                bookingService.bookSeat(userId, scheduleId, seatInventoryId),
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
                lockBookingService.bookSeat(userId, scheduleId, seatInventoryId),
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

        long heldOrSoldCount = seatInventoryRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.HELD)
                + seatInventoryRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.SOLD);
        assertThat(heldOrSoldCount)
                .as("좌석 점유는 정확히 1건 (oversell=0)")
                .isEqualTo(1);

        assertThat(reservationRepository.countBySeatInventoryId(seatInventoryId))
                .as("동일 좌석 예약은 정확히 1건 (중복 예약=0)")
                .isEqualTo(1);
    }

    @FunctionalInterface
    interface BookingAction {
        BookingResult book(Long userId);
    }

    private void runConcurrently(int threadCount, BookingAction action,
                                  AtomicInteger successCount, AtomicInteger failCount,
                                  Queue<Throwable> unexpected)
            throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final long userId = (i % 10000) + 1; // user1~user10000 순환
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await(); // 모든 스레드 준비 후 동시 시작
                    BookingResult result = action.book(userId);
                    if (result instanceof BookingResult.Success) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet(); // SeatTaken/SoldOut — 정상 경쟁 패배
                    }
                } catch (OptimisticLockingFailureException | CannotAcquireLockException e) {
                    failCount.incrementAndGet(); // 경합 패배 — 정상 실패 경로
                } catch (Throwable t) {
                    unexpected.add(t);           // 경합과 무관한 진짜 이상 — 별도 수집
                    failCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
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
