package com.ktx.ticketing.booking;

import com.ktx.ticketing.admission.EntrySession;
import com.ktx.ticketing.admission.EntryTokenStore;
import com.ktx.ticketing.domain.Reservation;
import com.ktx.ticketing.domain.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ReservationController 슬라이스 테스트 — 컨트롤러 고유 로직인 "토큰 게이트 + ReservationCommandResult→HTTP
 * 매핑 + 성공 시 토큰 revoke + 신뢰 경계(토큰의 userId 로 호출)"를 검증한다.
 */
@WebMvcTest(ReservationController.class)
class ReservationControllerTest {

    private static final String TOKEN = "valid-token";
    private static final long SCHEDULE_ID = 1L;
    private static final long USER_ID = 7L;
    private static final long RESERVATION_ID = 100L;

    @Autowired MockMvc mvc;

    @MockitoBean EntryTokenStore tokenStore;
    @MockitoBean ReservationLifecycleService lifecycleService;

    private void givenValidToken() {
        when(tokenStore.resolve(TOKEN)).thenReturn(new EntrySession(SCHEDULE_ID, USER_ID));
    }

    private static ReservationCommandResult.Success success(ReservationStatus status) {
        Reservation r = mock(Reservation.class);
        when(r.getId()).thenReturn(RESERVATION_ID);
        when(r.getStatus()).thenReturn(status);
        return new ReservationCommandResult.Success(r);
    }

    // --- confirm ---

    @Test
    void confirm_토큰_없으면_401_확정시도_안함() throws Exception {
        mvc.perform(post("/api/reservations/{id}/confirm", RESERVATION_ID))
                .andExpect(status().isUnauthorized());

        verify(lifecycleService, never()).confirm(anyLong(), anyLong());
    }

    @Test
    void confirm_무효_또는_만료_토큰이면_401_확정시도_안함() throws Exception {
        when(tokenStore.resolve("bad")).thenReturn(null); // 헤더는 있으나 resolve 실패(만료/위조)

        mvc.perform(post("/api/reservations/{id}/confirm", RESERVATION_ID)
                        .header(ReservationController.ENTRY_TOKEN_HEADER, "bad"))
                .andExpect(status().isUnauthorized());

        verify(lifecycleService, never()).confirm(anyLong(), anyLong());
    }

    @Test
    void confirm_성공시_200_바디_그리고_토큰의_userId로_호출_토큰_revoke() throws Exception {
        givenValidToken();
        // Success(reservation mock)는 when() 바깥에서 먼저 만든다 — thenReturn 인자 안에서 만들면
        // 바깥 스터빙이 끝나기 전 새 스터빙이 열려 UnfinishedStubbingException 이 난다.
        ReservationCommandResult.Success confirmed = success(ReservationStatus.CONFIRMED);
        when(lifecycleService.confirm(RESERVATION_ID, USER_ID)).thenReturn(confirmed);

        mvc.perform(post("/api/reservations/{id}/confirm", RESERVATION_ID)
                        .header(ReservationController.ENTRY_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(100))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 신뢰 경계: path 의 id + 토큰의 userId 로 호출. 세션 종료 → 토큰 회수.
        verify(lifecycleService).confirm(RESERVATION_ID, USER_ID);
        verify(tokenStore).revoke(TOKEN);
    }

    @Test
    void confirm_NotFound는_404_토큰_revoke_안함() throws Exception {
        givenValidToken();
        when(lifecycleService.confirm(RESERVATION_ID, USER_ID))
                .thenReturn(new ReservationCommandResult.NotFound());

        mvc.perform(post("/api/reservations/{id}/confirm", RESERVATION_ID)
                        .header(ReservationController.ENTRY_TOKEN_HEADER, TOKEN))
                .andExpect(status().isNotFound());

        verify(tokenStore, never()).revoke(anyString()); // 실패 시 세션 유지
    }

    @Test
    void confirm_Forbidden은_403() throws Exception {
        givenValidToken();
        when(lifecycleService.confirm(RESERVATION_ID, USER_ID))
                .thenReturn(new ReservationCommandResult.Forbidden());

        mvc.perform(post("/api/reservations/{id}/confirm", RESERVATION_ID)
                        .header(ReservationController.ENTRY_TOKEN_HEADER, TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void confirm_IllegalState는_409() throws Exception {
        givenValidToken();
        when(lifecycleService.confirm(RESERVATION_ID, USER_ID))
                .thenReturn(new ReservationCommandResult.IllegalState());

        mvc.perform(post("/api/reservations/{id}/confirm", RESERVATION_ID)
                        .header(ReservationController.ENTRY_TOKEN_HEADER, TOKEN))
                .andExpect(status().isConflict());
    }

    // --- cancel ---

    @Test
    void cancel_토큰_없으면_401_취소시도_안함() throws Exception {
        mvc.perform(delete("/api/reservations/{id}", RESERVATION_ID))
                .andExpect(status().isUnauthorized());

        verify(lifecycleService, never()).cancel(anyLong(), anyLong());
    }

    @Test
    void cancel_무효_또는_만료_토큰이면_401_취소시도_안함() throws Exception {
        when(tokenStore.resolve("bad")).thenReturn(null);

        mvc.perform(delete("/api/reservations/{id}", RESERVATION_ID)
                        .header(ReservationController.ENTRY_TOKEN_HEADER, "bad"))
                .andExpect(status().isUnauthorized());

        verify(lifecycleService, never()).cancel(anyLong(), anyLong());
    }

    @Test
    void cancel_성공시_204_그리고_토큰_revoke() throws Exception {
        givenValidToken();
        ReservationCommandResult.Success cancelled = success(ReservationStatus.CANCELLED);
        when(lifecycleService.cancel(RESERVATION_ID, USER_ID)).thenReturn(cancelled);

        mvc.perform(delete("/api/reservations/{id}", RESERVATION_ID)
                        .header(ReservationController.ENTRY_TOKEN_HEADER, TOKEN))
                .andExpect(status().isNoContent());

        verify(lifecycleService).cancel(RESERVATION_ID, USER_ID);
        verify(tokenStore).revoke(TOKEN);
    }

    @Test
    void cancel_IllegalState는_409_동일_매핑_경로() throws Exception {
        // 취소도 confirm 과 같은 toErrorResponse 매핑을 타는지 — 204 분기 외 에러 경로 확인
        givenValidToken();
        when(lifecycleService.cancel(RESERVATION_ID, USER_ID))
                .thenReturn(new ReservationCommandResult.IllegalState());

        mvc.perform(delete("/api/reservations/{id}", RESERVATION_ID)
                        .header(ReservationController.ENTRY_TOKEN_HEADER, TOKEN))
                .andExpect(status().isConflict());

        verify(tokenStore, never()).revoke(anyString());
    }
}
