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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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
class BookingIntegrationTest extends AbstractIntegrationTest {

    @Autowired AdmissionService admissionService;
    @Autowired AdmissionProperties admissionProperties;
    @Autowired BookingService bookingService;
    @Autowired ReservationLifecycleService lifecycleService;
    @Autowired HeldExpiryService heldExpiryService;
    @Autowired SeatPreemption preemption;
    @Autowired EntryTokenStore tokenStore;
    @Autowired ExpiryProperties expiryProperties;
    @Autowired ReservationLifecycleTransactionHelper txHelper;
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

        // HELD_TTL(5분) 이후 시각을 가진 sweep 오케스트레이터를 직접 조립해 만료를 결정적으로 재현한다.
        // 프로덕션 Clock 빈은 건드리지 않아 다른 테스트에 영향이 없다. 협력자는 컨텍스트 빈을 그대로 재사용.
        // expiresAt 은 프로덕션 Clock(systemDefaultZone) 으로 저장되므로 sweep Clock 의 zone 도 일치시켜야
        // LocalDateTime 비교(expiresAt < now)가 어긋나지 않는다(UTC 로 고정하면 KST 와 9h skew 발생).
        Clock future = Clock.fixed(
                Instant.now().plus(Reservation.HELD_TTL).plusSeconds(60), ZoneId.systemDefault());
        HeldExpiryService futureSweeper = new HeldExpiryService(
                reservationRepository, txHelper, preemption, admissionService,
                expiryProperties, future);

        int expired = futureSweeper.sweep();

        // 미래 Clock sweep 은 컨텍스트 공유 DB 의 다른 테스트 잔여 HELD 까지 만료시킬 수 있으므로(정상 동작)
        // 건수는 "최소 1" 로만 보고, 결정적 검증은 이 테스트가 만든 좌석/예약 스코프 단언으로 한다.
        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(seatStatus(targetSeat)).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(reservationStatus(reservationId)).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(preemption.availableSeatIds(scheduleId)).contains(targetSeat).hasSize(3);
        assertThat(activeCount()).isZero();
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
    @DisplayName("§3.2 입장 초과: 활성자 ≥ K → Rejected(Retry-After)")
    void admission_상한초과시_Rejected() {
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
