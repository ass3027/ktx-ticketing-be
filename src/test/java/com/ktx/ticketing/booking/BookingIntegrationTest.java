package com.ktx.ticketing.booking;

import com.ktx.ticketing.admission.AdmissionProperties;
import com.ktx.ticketing.admission.AdmissionResult;
import com.ktx.ticketing.admission.AdmissionService;
import com.ktx.ticketing.admission.EntrySession;
import com.ktx.ticketing.admission.EntryToken;
import com.ktx.ticketing.admission.EntryTokenStore;
import com.ktx.ticketing.domain.Reservation;
import com.ktx.ticketing.domain.ReservationRepository;
import com.ktx.ticketing.domain.ReservationStatus;
import com.ktx.ticketing.domain.Schedule;
import com.ktx.ticketing.domain.Seat;
import com.ktx.ticketing.domain.SeatInventory;
import com.ktx.ticketing.domain.SeatInventoryRepository;
import com.ktx.ticketing.domain.SeatStatus;
import com.ktx.ticketing.domain.Train;
import com.ktx.ticketing.domain.User;
import com.ktx.ticketing.support.AbstractIntegrationTest;
import com.ktx.ticketing.support.MutableClock;
import com.ktx.ticketing.support.TestClockConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T3-11: 정합성 자동화 통합 테스트 (M3 DoD = 정상 E2E + 예외 6종).
 *
 * <p>{@link AbstractIntegrationTest} 의 실 MySQL/Redis(Testcontainers)에서 서비스 계층을 직접 구동해
 * "입장→예매→확정/취소/만료" 생명주기와 그에 수반되는 <b>좌석 상태 / 가용 풀(avail) / 활성자 카운터</b>
 * 동기화를 한 흐름으로 검증한다. HTTP 401/429 매핑은 컨트롤러 슬라이스
 * ({@code BookingControllerTest}/{@code ReservationControllerTest})가 이미 커버하므로 중복하지 않고,
 * 여기서는 예외 6종을 발생 <b>지점</b>(서비스 반환값/AdmissionResult/토큰 해석)에서 확인한다.
 *
 * <p>동시성(1,000요청 oversell=0)·reconcile 수렴은 {@link ConcurrencyPocTest}/{@code ReconciliationIntegrationTest}
 * 가 이미 통합 검증하므로 재작성하지 않는다.
 *
 * <p>시드: 도메인 엔티티 + JPA 로 작은 통제 세계(train/schedule/seat×3/seat_inventory×3/user×3)를
 * 컨텍스트(=DB)당 1회 저장한다(다른 통합 테스트와 동일 패턴). 토큰 발급은 실제 흐름대로
 * {@link AdmissionService#tryEnter}만 사용하고(스토어 직접 발급 X), 세션의 userId 로 예매/확정/취소를 호출해
 * 신뢰 경계를 지킨다.
 */
@Import(TestClockConfig.class)
class BookingIntegrationTest extends AbstractIntegrationTest {

    @Autowired AdmissionService admissionService;
    @Autowired AdmissionProperties admissionProperties;
    @Autowired BookingService bookingService;
    @Autowired ReservationLifecycleService lifecycleService;
    @Autowired HeldExpiryService heldExpiryService;
    @Autowired SeatPreemption preemption;
    @Autowired EntryTokenStore tokenStore;
    @Autowired MutableClock mutableClock;
    @Autowired SeatInventoryRepository seatInventoryRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired StringRedisTemplate redis;

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    private long scheduleId;
    private List<Long> seatIds; // 이 스케줄의 AVAILABLE 좌석 inventory id 3개

    @BeforeEach
    void setUp() {
        // 미래 출발 스케줄 + 좌석 3개를 시드. train_number 는 UNIQUE 이고 DB 가 컨텍스트 캐시로 공유되므로
        // 매 메서드 고유 train_number 를 부여해 중복 INSERT 충돌을 피한다(각 메서드는 자신만의 세계를 가짐).
        String trainNo = "KTX-it-" + UNIQUE.incrementAndGet();
        LocalDateTime depart = LocalDateTime.of(2026, 12, 1, 8, 0);
        tx.executeWithoutResult(status -> {
            Train train = new Train("KTX-1", trainNo);
            em.persist(train);
            Schedule schedule = new Schedule(train, "서울", "부산", depart, depart.plusHours(2), 3);
            em.persist(schedule);
            for (int n = 1; n <= 3; n++) {
                Seat seat = new Seat(train, 1, "it-" + n);
                em.persist(seat);
                em.persist(new SeatInventory(schedule, seat));
            }
            // 선점 승자의 Reservation.user_id FK 가 실제 user 행을 참조해야 하므로 user 도 시드.
            for (int n = 1; n <= 3; n++) {
                em.persist(new User("it-user" + UNIQUE.get() + "-" + n + "@ktx.test", "it-user" + n));
            }
            em.flush();
            scheduleId = schedule.getId();
        });
        seatIds = seatInventoryRepository.findAvailableIdsByScheduleId(scheduleId);
        assertThat(seatIds).hasSize(3);

        // 가용 풀(Redis)을 DB 와 일치하는 baseline 으로 초기화.
        preemption.initInventory(scheduleId, seatIds);
        // 테스트 간 시각 오염 방지: 매 테스트 전 START 로 리셋.
        mutableClock.reset(TestClockConfig.START);
    }

    @AfterEach
    void cleanup() {
        redis.delete("avail:" + scheduleId);
        redis.delete("preempt:ts:" + scheduleId);
        redis.delete("active:" + scheduleId);
    }

    // --- 정상 E2E: 입장 → 예매 → 확정 ---

    @Test
    @DisplayName("정상 E2E(SEAT): 입장→예매(HELD)→확정(SOLD), 좌석·avail·active 동기화")
    void seatMode_입장부터_확정까지_E2E() {
        long targetSeat = seatIds.get(0);

        EntrySession session = enter();
        assertThat(activeCount()).isEqualTo(1); // 입장 시 활성 슬롯 +1

        BookingResult booked = bookingService.bookSeat(session.userId(), scheduleId, targetSeat);

        assertThat(booked).isInstanceOf(BookingResult.Success.class);
        assertThat(seatStatus(targetSeat)).isEqualTo(SeatStatus.HELD);
        assertThat(preemption.availableSeatIds(scheduleId))
                .as("선점된 좌석은 가용 풀에서 빠진다")
                .doesNotContain(targetSeat).hasSize(2);

        Long reservationId = ((BookingResult.Success) booked).reservation().getId();
        ReservationCommandResult confirmed = lifecycleService.confirm(reservationId, session.userId());

        assertThat(confirmed).isInstanceOf(ReservationCommandResult.Success.class);
        assertThat(seatStatus(targetSeat)).isEqualTo(SeatStatus.SOLD);
        assertThat(reservationStatus(reservationId)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(activeCount())
                .as("확정(세션 종료) 시 활성 슬롯 반환")
                .isZero();
    }

    @Test
    @DisplayName("정상 E2E(AUTO): 입장→자동배정(HELD)→확정(SOLD)")
    void autoMode_입장부터_확정까지_E2E() {
        EntrySession session = enter();

        BookingResult booked = bookingService.bookAuto(session.userId(), scheduleId);

        assertThat(booked).isInstanceOf(BookingResult.Success.class);
        Reservation reservation = ((BookingResult.Success) booked).reservation();
        long assignedSeat = reservation.getSeatInventory().getId();
        assertThat(seatIds).as("배정된 좌석은 이 스케줄의 좌석이어야 한다").contains(assignedSeat);
        assertThat(seatStatus(assignedSeat)).isEqualTo(SeatStatus.HELD);
        assertThat(preemption.availableSeatIds(scheduleId)).doesNotContain(assignedSeat).hasSize(2);

        ReservationCommandResult confirmed = lifecycleService.confirm(reservation.getId(), session.userId());

        assertThat(confirmed).isInstanceOf(ReservationCommandResult.Success.class);
        assertThat(seatStatus(assignedSeat)).isEqualTo(SeatStatus.SOLD);
        assertThat(activeCount()).isZero();
    }

    // --- §3.4 취소 복구 ---

    @Test
    @DisplayName("§3.4 취소: HELD 취소 → 좌석 AVAILABLE 복구 + avail 반환 + active DECR")
    void cancel_HELD를_취소하면_좌석과_카운터가_복구된다() {
        long targetSeat = seatIds.get(0);
        EntrySession session = enter();
        BookingResult booked = bookingService.bookSeat(session.userId(), scheduleId, targetSeat);
        long reservationId = ((BookingResult.Success) booked).reservation().getId();

        ReservationCommandResult cancelled = lifecycleService.cancel(reservationId, session.userId());

        assertThat(cancelled).isInstanceOf(ReservationCommandResult.Success.class);
        assertThat(seatStatus(targetSeat)).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(reservationStatus(reservationId)).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(preemption.availableSeatIds(scheduleId))
                .as("취소된 좌석은 가용 풀로 되돌아온다")
                .contains(targetSeat).hasSize(3);
        assertThat(activeCount()).isZero();
    }

    // --- §3.3 만료 복구 ---

    @Test
    @DisplayName("§3.3 만료: HELD TTL 경과 → sweep 이 좌석 AVAILABLE 복구 + avail 반환 + active DECR")
    void expirySweep_만료된_HELD를_복구한다() {
        long targetSeat = seatIds.get(0);
        EntrySession session = enter();
        BookingResult booked = bookingService.bookSeat(session.userId(), scheduleId, targetSeat);
        long reservationId = ((BookingResult.Success) booked).reservation().getId();
        assertThat(seatStatus(targetSeat)).isEqualTo(SeatStatus.HELD);

        // MutableClock 을 HELD_TTL + 여유 1분 앞당겨 heldExpiryService 가 만료 대상으로 인식하게 한다.
        // expiresAt 은 mutableClock(systemDefault zone) 기준으로 저장됐으므로 zone skew 없음.
        mutableClock.advance(Reservation.HELD_TTL.plusSeconds(60));
        int expired = heldExpiryService.sweep();

        // sweep 은 컨텍스트 공유 DB 의 타 테스트 잔여 HELD 도 만료시킬 수 있으므로 건수는 "최소 1" 로만 확인.
        // 결정적 정합성 단언은 이 테스트의 좌석/예약 스코프로 한정한다.
        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(seatStatus(targetSeat)).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(reservationStatus(reservationId)).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(preemption.availableSeatIds(scheduleId)).contains(targetSeat).hasSize(3);
        assertThat(activeCount()).isZero();
    }

    // --- T4-13: confirm vs sweep 경합 (오버셀 0 — 벌크화 함정 회귀 가드) ---

    @Test
    @DisplayName("T4-13 confirm 선행: 이미 확정(SOLD)된 좌석은 만료 시각이 지나도 sweep 이 가용 풀에 반환하지 않는다(오버셀 0)")
    void confirm된_좌석은_sweep이_가용풀에_반환하지_않는다() {
        long targetSeat = seatIds.get(0);
        EntrySession session = enter();
        long reservationId = ((BookingResult.Success) bookingService.bookSeat(session.userId(), scheduleId, targetSeat))
                .reservation().getId();

        // 사용자가 만료 전에 확정 → 좌석 SOLD, 예약 CONFIRMED, 가용 풀에서 빠진 상태 유지.
        lifecycleService.confirm(reservationId, session.userId());
        assertThat(seatStatus(targetSeat)).isEqualTo(SeatStatus.SOLD);

        // 이후 만료 시각을 넘겨 sweep — expiresAt < now 라 findExpiredHeldIds 가 이 행을 조회할 수 있으나,
        // expire() 의 상태 재확인(status != HELD → null)이 CONFIRMED 행을 no-op 처리한다.
        // 벌크 UPDATE + SELECT 결과로 부수효과를 돌렸다면 SOLD 좌석이 avail 로 새어 오버셀이 났을 지점.
        mutableClock.advance(Reservation.HELD_TTL.plusSeconds(60));
        heldExpiryService.sweep();

        assertThat(seatStatus(targetSeat)).as("SOLD 좌석은 그대로").isEqualTo(SeatStatus.SOLD);
        assertThat(reservationStatus(reservationId)).as("확정은 만료로 뒤집히지 않는다").isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(preemption.availableSeatIds(scheduleId))
                .as("SOLD 좌석은 가용 풀에 반환되지 않는다(오버셀 0)")
                .doesNotContain(targetSeat);
    }

    @Test
    @DisplayName("T4-13 동시 경합: 같은 HELD 예약에 confirm 과 sweep 을 동시 실행 → @Version 으로 하나만 성공, 좌석 상태 일관(오버셀 0)")
    void confirm과_sweep_동시경합시_하나만_성공() throws Exception {
        long targetSeat = seatIds.get(0);
        EntrySession session = enter();
        long reservationId = ((BookingResult.Success) bookingService.bookSeat(session.userId(), scheduleId, targetSeat))
                .reservation().getId();

        // sweep 의 만료 판정 시각이 지나도록 앞당긴다(두 경로가 같은 HELD 행을 동시에 노림).
        mutableClock.advance(Reservation.HELD_TTL.plusSeconds(60));

        // confirm 과 sweep 을 같은 출발선에서 동시 발사 — @Version 충돌 시 한쪽은 OptimisticLock 으로 실패한다.
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Boolean> confirmTask = pool.submit(() -> {
                start.await();
                try {
                    return lifecycleService.confirm(reservationId, session.userId())
                            instanceof ReservationCommandResult.Success;
                } catch (Exception e) {
                    return false; // 경합 패배(OptimisticLock 등) → 확정 실패
                }
            });
            java.util.concurrent.Future<Boolean> sweepTask = pool.submit(() -> {
                start.await();
                try {
                    return heldExpiryService.sweep() >= 1; // 이 행을 만료시켰는지
                } catch (Exception e) {
                    return false;
                }
            });
            start.countDown();
            confirmTask.get(10, java.util.concurrent.TimeUnit.SECONDS);
            sweepTask.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 최종 상태는 CONFIRMED(좌석 SOLD) 또는 EXPIRED(좌석 AVAILABLE) 중 정확히 하나로 일관해야 한다.
        // 어느 쪽이든 "좌석 SOLD인데 avail 에도 있는" 오버셀은 없어야 한다.
        ReservationStatus finalStatus = reservationStatus(reservationId);
        boolean seatInAvail = preemption.availableSeatIds(scheduleId).contains(targetSeat);
        if (finalStatus == ReservationStatus.CONFIRMED) {
            assertThat(seatStatus(targetSeat)).isEqualTo(SeatStatus.SOLD);
            assertThat(seatInAvail).as("SOLD 인데 가용 풀에도 있으면 오버셀").isFalse();
        } else {
            assertThat(finalStatus).as("승자는 confirm 또는 sweep 둘 중 하나").isEqualTo(ReservationStatus.EXPIRED);
            assertThat(seatStatus(targetSeat)).isEqualTo(SeatStatus.AVAILABLE);
            assertThat(seatInAvail).as("만료 좌석은 가용 풀로 정확히 1회 반환").isTrue();
        }
    }

    // --- B-2: 되돌아온 좌석 재예매 (활성 한정 부분 유니크) ---

    @Test
    @DisplayName("B-2 취소 후 재예매: 취소로 되돌아온 좌석을 같은 좌석으로 다시 예매하면 성공(과거엔 전역 유니크로 500)")
    void cancel_후_같은좌석_재예매_성공() {
        long targetSeat = seatIds.get(0);

        EntrySession first = enter();
        long firstResId = ((BookingResult.Success) bookingService.bookSeat(first.userId(), scheduleId, targetSeat))
                .reservation().getId();
        lifecycleService.cancel(firstResId, first.userId()); // 좌석 AVAILABLE + avail 복귀

        // 같은 좌석 재예매: 전역 유니크였다면 reservation INSERT 가 Duplicate entry → 500.
        // 부분 유니크(취소 행은 active=NULL)에서는 공존 가능 → 정상 HELD.
        EntrySession second = enter();
        BookingResult rebooked = bookingService.bookSeat(second.userId(), scheduleId, targetSeat);

        assertThat(rebooked).isInstanceOf(BookingResult.Success.class);
        long secondResId = ((BookingResult.Success) rebooked).reservation().getId();
        assertThat(secondResId).isNotEqualTo(firstResId); // 새 예약 행
        assertThat(seatStatus(targetSeat)).isEqualTo(SeatStatus.HELD);
        assertThat(reservationStatus(firstResId)).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservationStatus(secondResId)).isEqualTo(ReservationStatus.HELD);
    }

    @Test
    @DisplayName("B-2 만료 후 재예매: HELD 만료 sweep 으로 되돌아온 좌석을 다시 예매하면 성공")
    void expiry_후_같은좌석_재예매_성공() {
        long targetSeat = seatIds.get(0);

        EntrySession first = enter();
        long firstResId = ((BookingResult.Success) bookingService.bookSeat(first.userId(), scheduleId, targetSeat))
                .reservation().getId();
        mutableClock.advance(Reservation.HELD_TTL.plusSeconds(60));
        heldExpiryService.sweep(); // 좌석 AVAILABLE + avail 복귀, 예약 EXPIRED

        EntrySession second = enter();
        BookingResult rebooked = bookingService.bookSeat(second.userId(), scheduleId, targetSeat);

        assertThat(rebooked).isInstanceOf(BookingResult.Success.class);
        assertThat(reservationStatus(firstResId)).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservationStatus(((BookingResult.Success) rebooked).reservation().getId()))
                .isEqualTo(ReservationStatus.HELD);
    }

    @Test
    @DisplayName("B-2 불변식 유지: 한 좌석에 '활성' 예약 2건은 부분 유니크(uk_active_seat)가 차단")
    void 한좌석_활성예약_2건은_차단된다() {
        // 서비스 경로는 Redis 선점(SREM)이 승자 1명만 통과시켜 DB 까지 2건이 못 간다.
        // 부분 유니크 제약 자체가 살아있는지(회귀 가드)만 보려면 DB write 를 직접 두 번 시도해야 한다.
        // 좌석 상태/version 과 무관하게 reservation 의 제약만 때리려고 native INSERT 로 같은 좌석 활성 2건을 넣는다
        // (Reservation.hold 는 좌석까지 markHeld 하므로 좌석 제약과 섞임 → 순수 검증엔 부적합).
        long targetSeat = seatIds.get(0);
        EntrySession s1 = enter();
        bookingService.bookSeat(s1.userId(), scheduleId, targetSeat); // 활성 HELD 1건 (active = targetSeat)
        long otherUserId = enter().userId();

        // em 직접 실행이라 Spring 예외 변환 계층(@Repository)을 안 거쳐 Hibernate 원본 예외가 나온다.
        // 검증의 본질은 "uk_active_seat 가 같은 좌석 활성 2건을 막는다"이므로 메시지로 제약명을 단언한다.
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                em.createNativeQuery("""
                        INSERT INTO reservation(user_id, seat_inventory_id, status, held_at, expires_at)
                        VALUES (?1, ?2, 'HELD', NOW(), NOW())
                        """)
                        .setParameter(1, otherUserId)
                        .setParameter(2, targetSeat)
                        .executeUpdate()))
                .as("같은 좌석 활성 2건째는 uk_active_seat 위반")
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("uk_active_seat");
    }

    // --- §3.1 매진 / 이미 선점된 좌석 시도 ---

    @Test
    @DisplayName("§3.1 매진: 가용 풀이 빈 스케줄에 AUTO 예매 → SoldOut, DB 무변화")
    void autoMode_잔여석없으면_SoldOut() {
        EntrySession session = enter();
        // 가용 풀을 비워 매진 상태로 만든다(DB 좌석은 AVAILABLE 그대로).
        redis.delete("avail:" + scheduleId);

        BookingResult result = bookingService.bookAuto(session.userId(), scheduleId);

        assertThat(result).isInstanceOf(BookingResult.SoldOut.class);
        // 이 스케줄에 대해 어떤 좌석도 점유되지 않았고(HELD 0), 예약도 생기지 않았다.
        // (count() 전역 단언은 컨텍스트 공유 DB 에서 다른 테스트 잔여 예약에 오염되므로 스케줄 스코프로 본다.)
        assertThat(seatInventoryRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.HELD)).isZero();
        assertThat(seatIds.stream().mapToLong(reservationRepository::countBySeatInventoryId).sum())
                .as("매진 응답은 예약 행을 만들지 않는다")
                .isZero();
    }

    @Test
    @DisplayName("§3.1 경쟁 패배: 이미 선점된 좌석을 SEAT 예매 → SeatTaken")
    void seatMode_이미선점된_좌석은_SeatTaken() {
        long targetSeat = seatIds.get(0);
        EntrySession first = enter();
        bookingService.bookSeat(first.userId(), scheduleId, targetSeat); // 선점 승자

        EntrySession second = enter();
        BookingResult result = bookingService.bookSeat(second.userId(), scheduleId, targetSeat);

        assertThat(result).isInstanceOf(BookingResult.SeatTaken.class);
        assertThat(seatInventoryRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.HELD))
                .as("좌석은 정확히 1건만 HELD (oversell=0)")
                .isEqualTo(1);
        assertThat(reservationRepository.countBySeatInventoryId(targetSeat)).isEqualTo(1);
    }

    // --- §3.2 입장 초과 ---

    @Test
    @DisplayName("§3.2 입장 초과: 활성자 ≥ K → Rejected(Retry-After), 퇴장 후 재입장 허용")
    void admission_상한초과시_Rejected_퇴장후_재입장_허용() {
        int k = admissionProperties.maxActive();
        // 한도(K)까지 입장 — 모두 Admitted.
        for (int i = 0; i < k; i++) {
            assertThat(admissionService.tryEnter(scheduleId, (long) (i + 1)))
                    .isInstanceOf(AdmissionResult.Admitted.class);
        }
        // K+1 번째는 거절.
        AdmissionResult overflow = admissionService.tryEnter(scheduleId, (long) (k + 1));

        assertThat(overflow).isInstanceOf(AdmissionResult.Rejected.class);
        assertThat(((AdmissionResult.Rejected) overflow).retryAfter()).isNotNull();
        assertThat(activeCount())
                .as("거절은 INCR 을 롤백하므로 활성자는 K 를 넘지 않는다")
                .isEqualTo(k);

        // 기존 사용자 1명 퇴장(세션 종료) → 슬롯 반환.
        admissionService.leave(scheduleId);
        assertThat(activeCount()).isEqualTo(k - 1);

        // 퇴장 후 재시도 → Admitted(슬롯 회복 확인).
        AdmissionResult retry = admissionService.tryEnter(scheduleId, (long) (k + 2));
        assertThat(retry)
                .as("슬롯이 반환됐으므로 재시도는 Admitted")
                .isInstanceOf(AdmissionResult.Admitted.class);
        assertThat(activeCount()).isEqualTo(k);
    }

    // --- §3.5 토큰 없음 ---

    @Test
    @DisplayName("§3.5 토큰 없음: 무효 토큰 해석 → null (예매 게이트 차단 근거)")
    void resolve_무효토큰은_null() {
        assertThat(tokenStore.resolve("nonexistent-token")).isNull();
    }

    // --- helpers ---

    /** 실제 흐름대로 입장 제어를 통과해 토큰을 받고, 그 세션(scheduleId/userId)을 돌려준다. */
    private EntrySession enter() {
        AdmissionResult result = admissionService.tryEnter(scheduleId, 1L);
        assertThat(result).isInstanceOf(AdmissionResult.Admitted.class);
        EntryToken token = ((AdmissionResult.Admitted) result).token();
        EntrySession session = tokenStore.resolve(token.value());
        assertThat(session).as("발급 직후 토큰은 해석 가능해야 한다").isNotNull();
        return session;
    }

    private SeatStatus seatStatus(long seatInventoryId) {
        return seatInventoryRepository.findById(seatInventoryId).orElseThrow().getStatus();
    }

    private ReservationStatus reservationStatus(long reservationId) {
        return reservationRepository.findById(reservationId).orElseThrow().getStatus();
    }

    private long activeCount() {
        String value = redis.opsForValue().get("active:" + scheduleId);
        return value == null ? 0 : Long.parseLong(value);
    }
}
