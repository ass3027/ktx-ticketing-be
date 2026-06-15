package com.ktx.ticketing.admission;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EntryController 슬라이스 테스트 — "AdmissionResult → HTTP 매핑"을 검증한다.
 * Admitted → 201 + 토큰, Rejected → 429 + Retry-After.
 */
@WebMvcTest(EntryController.class)
class EntryControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean AdmissionService admissionService;

    private String body(long scheduleId, long userId) throws Exception {
        return json.writeValueAsString(new EntryController.EntryRequest(scheduleId, userId));
    }

    @Test
    void 입장_허용시_201_토큰_반환() throws Exception {
        when(admissionService.tryEnter(1L, 7L))
                .thenReturn(new AdmissionResult.Admitted(new EntryToken("tok-abc")));

        mvc.perform(post("/api/entry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1L, 7L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("tok-abc"));
    }

    @Test
    void 활성자_초과시_429_RetryAfter_헤더() throws Exception {
        when(admissionService.tryEnter(1L, 7L))
                .thenReturn(new AdmissionResult.Rejected(Duration.ofSeconds(5)));

        mvc.perform(post("/api/entry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1L, 7L)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"));
    }
}
