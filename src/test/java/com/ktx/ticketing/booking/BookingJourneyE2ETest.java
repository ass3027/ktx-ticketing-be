package com.ktx.ticketing.booking;

import com.ktx.ticketing.admission.EntryController;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T3-11 E2E: HTTP → 서비스 → DB/Redis 전 구간을 한 번에 통과하는 엔드-투-엔드 정합성 테스트.
 *
 * <p>컨트롤러 슬라이스 테스트({@code BookingControllerTest} 등)는 서비스를 모킹하고,
 * {@link BookingIntegrationTest}는 HTTP를 우회한다. 이 테스트는 그 갭 — 실제 토큰이 Redis에 저장·조회되고
 * 컨트롤러가 서비스를 실제로 호출하는 전 구간 — 을 검증한다.
 *
 * <p>만료 sweep 복구는 {@link BookingIntegrationTest}가 담당하므로 여기서 중복하지 않는다.
 * 동시성·reconcile은 기존 통합 테스트({@code ConcurrencyPocTest}/{@code ReconciliationIntegrationTest})를 인용.
 *
 * <p>booking.admission.max-active=3 오버라이드: 429 시나리오를 3번 HTTP 호출로 재현한다(기본값 100이면 100번).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "booking.admission.max-active=3")
class BookingJourneyE2ETest extends AbstractIntegrationTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();
    private static final String TOKEN_HEADER = "X-Entry-Token";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SeatInventoryRepository seatInventoryRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired SeatPreemption preemption;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired StringRedisTemplate redis;

    private long scheduleId;
    private List<Long> seatIds;
    private List<Long> userIds;

    @BeforeEach
    void setUp() {
        String trainNo = "KTX-e2e-" + UNIQUE.incrementAndGet();
        LocalDateTime depart = LocalDateTime.of(2027, 1, 1, 8, 0);
        List<Long> tempUserIds = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            Train train = new Train("KTX-1", trainNo);
            em.persist(train);
            Schedule schedule = new Schedule(train, "서울", "부산", depart, depart.plusHours(2), 3);
            em.persist(schedule);
            for (int n = 1; n <= 3; n++) {
                Seat seat = new Seat(train, 1, "e2e-" + n);
                em.persist(seat);
                em.persist(new SeatInventory(schedule, seat));
            }
            for (int n = 1; n <= 3; n++) {
                User user = new User("e2e-" + UNIQUE.get() + "-" + n + "@ktx.test", "e2e-user" + n);
                em.persist(user);
                tempUserIds.add(user.getId());
            }
            em.flush();
            scheduleId = schedule.getId();
        });
        userIds = tempUserIds;
        seatIds = seatInventoryRepository.findAvailableIdsByScheduleId(scheduleId);
        assertThat(seatIds).hasSize(3);
        preemption.initInventory(scheduleId, seatIds);
    }

    @AfterEach
    void cleanup() {
        redis.delete("avail:" + scheduleId);
        redis.delete("preempt:ts:" + scheduleId);
        redis.delete("active:" + scheduleId);
    }

    // --- 정상 E2E ---

    @Test
    @DisplayName("정상 E2E(SEAT): HTTP 입장→예매(HELD)→확정(SOLD) + DB/Redis 정합 + 토큰 revoke 확인")
    void seatMode_HTTP_정상_E2E() throws Exception {
        long targetSeat = seatIds.get(0);
        String token = enterHttp(userIds.get(0));
        assertThat(activeCount()).isEqualTo(1);

        long reservationId = bookSeatHttp(token, targetSeat);
        assertThat(seatStatus(targetSeat)).isEqualTo(SeatStatus.HELD);
        assertThat(preemption.availableSeatIds(scheduleId)).doesNotContain(targetSeat).hasSize(2);

        confirmHttp(token, reservationId);
        assertThat(seatStatus(targetSeat)).isEqualTo(SeatStatus.SOLD);
        assertThat(reservationStatus(reservationId)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(activeCount()).isZero();

        // 확정 후 토큰 revoke 확인 — 재확정 시도 → 401.
        mvc.perform(post("/api/reservations/{id}/confirm", reservationId)
                        .header(TOKEN_HEADER, token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("정상 E2E(AUTO): HTTP 입장→자동배정(HELD)→확정(SOLD)")
    void autoMode_HTTP_정상_E2E() throws Exception {
        String token = enterHttp(userIds.get(0));

        long reservationId = bookAutoHttp(token);
        assertThat(seatInventoryRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.HELD))
                .as("AUTO 배정 후 정확히 1좌석 HELD").isEqualTo(1);
        assertThat(preemption.availableSeatIds(scheduleId)).hasSize(2);

        confirmHttp(token, reservationId);
        assertThat(seatInventoryRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.SOLD))
                .isEqualTo(1);
        assertThat(activeCount()).isZero();
    }

    // --- HTTP 예외 ---

    @Test
    @DisplayName("401: X-Entry-Token 헤더 누락 → 401, 예매 시도 없음")
    void 토큰_헤더_누락시_401() throws Exception {
        String reqBody = json.writeValueAsString(
                new BookingController.ReservationRequest(BookingMode.AUTO, null));
        mvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqBody))
                .andExpect(status().isUnauthorized());

        assertThat(seatInventoryRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.HELD)).isZero();
    }

    @Test
    @DisplayName("401: Redis에 없는 무효 토큰 → 401, DB/Redis 무변화")
    void 무효_토큰시_401() throws Exception {
        String reqBody = json.writeValueAsString(
                new BookingController.ReservationRequest(BookingMode.AUTO, null));
        mvc.perform(post("/api/reservations")
                        .header(TOKEN_HEADER, "nonexistent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqBody))
                .andExpect(status().isUnauthorized());

        assertThat(seatInventoryRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.HELD)).isZero();
    }

    @Test
    @DisplayName("429: 활성자 ≥ K(3) → 429 + Retry-After 헤더, INCR 롤백으로 active 유지")
    void 입장초과시_429_RetryAfter_헤더() throws Exception {
        enterHttp(userIds.get(0));
        enterHttp(userIds.get(1));
        enterHttp(userIds.get(2)); // K=3 채움

        mvc.perform(post("/api/entry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new EntryController.EntryRequest(scheduleId, 999L))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));

        assertThat(activeCount()).as("INCR 롤백 → active 는 K=3 그대로").isEqualTo(3);
    }

    @Test
    @DisplayName("204: HELD 취소 → 좌석 AVAILABLE + avail 반환 + active DECR + 토큰 revoke")
    void 취소시_204_좌석_복구() throws Exception {
        long targetSeat = seatIds.get(0);
        String token = enterHttp(userIds.get(0));
        long reservationId = bookSeatHttp(token, targetSeat);

        mvc.perform(delete("/api/reservations/{id}", reservationId)
                        .header(TOKEN_HEADER, token))
                .andExpect(status().isNoContent());

        assertThat(seatStatus(targetSeat)).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(reservationStatus(reservationId)).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(preemption.availableSeatIds(scheduleId)).contains(targetSeat).hasSize(3);
        assertThat(activeCount()).isZero();
    }

    @Test
    @DisplayName("409: 이미 선점된 좌석 SEAT 예매 → 409, oversell=0")
    void SEAT_경쟁패배시_409() throws Exception {
        long targetSeat = seatIds.get(0);
        String token1 = enterHttp(userIds.get(0));
        bookSeatHttp(token1, targetSeat); // 선점 승자

        String token2 = enterHttp(userIds.get(1));
        String reqBody = json.writeValueAsString(
                new BookingController.ReservationRequest(BookingMode.SEAT, targetSeat));
        mvc.perform(post("/api/reservations")
                        .header(TOKEN_HEADER, token2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqBody))
                .andExpect(status().isConflict());

        assertThat(seatInventoryRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.HELD))
                .as("oversell=0: 정확히 1건만 HELD").isEqualTo(1);
    }

    @Test
    @DisplayName("410: 가용 풀 소진 AUTO 예매 → 410, DB 무변화")
    void AUTO_매진시_410() throws Exception {
        String token = enterHttp(userIds.get(0));
        redis.delete("avail:" + scheduleId); // 가용 풀 소진

        mvc.perform(post("/api/reservations")
                        .header(TOKEN_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new BookingController.ReservationRequest(BookingMode.AUTO, null))))
                .andExpect(status().isGone());

        assertThat(seatInventoryRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.HELD)).isZero();
    }

    // --- helpers ---

    private String enterHttp(long userId) throws Exception {
        String reqBody = json.writeValueAsString(new EntryController.EntryRequest(scheduleId, userId));
        String resp = mvc.perform(post("/api/entry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(resp, Map.class);
        return (String) body.get("token");
    }

    private long bookSeatHttp(String token, long seatId) throws Exception {
        String reqBody = json.writeValueAsString(
                new BookingController.ReservationRequest(BookingMode.SEAT, seatId));
        String resp = mvc.perform(post("/api/reservations")
                        .header(TOKEN_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(resp, Map.class);
        return ((Number) body.get("reservationId")).longValue();
    }

    private long bookAutoHttp(String token) throws Exception {
        String reqBody = json.writeValueAsString(
                new BookingController.ReservationRequest(BookingMode.AUTO, null));
        String resp = mvc.perform(post("/api/reservations")
                        .header(TOKEN_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(resp, Map.class);
        return ((Number) body.get("reservationId")).longValue();
    }

    private void confirmHttp(String token, long reservationId) throws Exception {
        mvc.perform(post("/api/reservations/{id}/confirm", reservationId)
                        .header(TOKEN_HEADER, token))
                .andExpect(status().isOk());
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
