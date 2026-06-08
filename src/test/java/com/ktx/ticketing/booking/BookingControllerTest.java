package com.ktx.ticketing.booking;

import com.ktx.ticketing.admission.EntrySession;
import com.ktx.ticketing.admission.EntryTokenStore;
import com.ktx.ticketing.domain.Reservation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BookingController 슬라이스 테스트 — 컨트롤러 고유 로직인 "토큰 게이트 + BookingResult→HTTP 매핑"을 검증한다.
 * 토큰은 헤더에서만, userId/scheduleId 는 토큰(EntrySession)에서만 취한다(신뢰 경계).
 */
@WebMvcTest(BookingController.class)
class BookingControllerTest {

    private static final String TOKEN = "valid-token";
    private static final long SCHEDULE_ID = 1L;
    private static final long USER_ID = 7L;
    private static final long SEAT_ID = 42L;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockitoBean EntryTokenStore tokenStore;
    @MockitoBean BookingService bookingService;

    private void givenValidToken() {
        when(tokenStore.resolve(TOKEN)).thenReturn(new EntrySession(SCHEDULE_ID, USER_ID));
    }

    private static Reservation reservation(long id, LocalDateTime expiresAt) {
        Reservation r = mock(Reservation.class);
        when(r.getId()).thenReturn(id);
        when(r.getExpiresAt()).thenReturn(expiresAt);
        return r;
    }

    private String body(String mode, @org.jspecify.annotations.Nullable Long seatId) throws Exception {
        return json.writeValueAsString(new BookingController.ReservationRequest(
                BookingMode.valueOf(mode), seatId));
    }

    @Test
    void 토큰_헤더_없으면_401_예매시도_안함() throws Exception {
        mvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("AUTO", null)))
                .andExpect(status().isUnauthorized());

        verify(bookingService, never()).bookAuto(anyLong(), anyLong());
    }

    @Test
    void 무효_토큰이면_401() throws Exception {
        when(tokenStore.resolve("bad")).thenReturn(null);

        mvc.perform(post("/api/reservations")
                        .header(BookingController.ENTRY_TOKEN_HEADER, "bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("AUTO", null)))
                .andExpect(status().isUnauthorized());

        verify(bookingService, never()).bookAuto(anyLong(), anyLong());
    }

    @Test
    void SEAT_모드인데_좌석_미지정이면_400() throws Exception {
        givenValidToken();

        mvc.perform(post("/api/reservations")
                        .header(BookingController.ENTRY_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SEAT", null)))
                .andExpect(status().isBadRequest());

        verify(bookingService, never()).bookSeat(anyLong(), anyLong(), any());
    }

    @Test
    void SEAT_성공시_201_그리고_토큰의_userId_scheduleId로_예매() throws Exception {
        givenValidToken();
        LocalDateTime expiresAt = LocalDateTime.of(2026, 7, 1, 8, 5);
        // reservation mock 은 when() 바깥에서 먼저 만든다 — thenReturn 인자 안에서 만들면
        // 바깥 스터빙이 끝나기 전 새 스터빙이 열려 UnfinishedStubbingException 이 난다.
        Reservation reserved = reservation(100L, expiresAt);
        when(bookingService.bookSeat(USER_ID, SCHEDULE_ID, SEAT_ID))
                .thenReturn(new BookingResult.Success(reserved));

        mvc.perform(post("/api/reservations")
                        .header(BookingController.ENTRY_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SEAT", SEAT_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId").value(100));

        // 신뢰 경계: body 가 아니라 토큰의 userId/scheduleId 로 호출돼야 한다.
        verify(bookingService).bookSeat(USER_ID, SCHEDULE_ID, SEAT_ID);
    }

    @Test
    void AUTO_선점패배_SeatTaken은_409() throws Exception {
        givenValidToken();
        when(bookingService.bookSeat(USER_ID, SCHEDULE_ID, SEAT_ID))
                .thenReturn(new BookingResult.SeatTaken());

        mvc.perform(post("/api/reservations")
                        .header(BookingController.ENTRY_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SEAT", SEAT_ID)))
                .andExpect(status().isConflict());
    }

    @Test
    void AUTO_잔여없음_SoldOut은_410() throws Exception {
        givenValidToken();
        when(bookingService.bookAuto(USER_ID, SCHEDULE_ID)).thenReturn(new BookingResult.SoldOut());

        mvc.perform(post("/api/reservations")
                        .header(BookingController.ENTRY_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("AUTO", null)))
                .andExpect(status().isGone());
    }
}
