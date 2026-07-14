package com.ktx.ticketing.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI(T6-3a) — FE 부재의 대체 시연 진입점. {@code /swagger-ui.html} 에서
 * 조회→입장→예매(SEAT/AUTO)→확정→취소를 클릭 호출한다.
 *
 * <p>예매·확정·취소는 {@code X-Entry-Token} 헤더를 요구한다. 글로벌 apiKey 보안 스킴으로 등록해
 * UI 상단 "Authorize" 에 토큰을 한 번 넣으면 보호된 엔드포인트에 자동 첨부되게 한다(시연 편의).
 * 토큰 자체는 {@code POST /api/entry} 응답으로 얻는다(발급처는 스킴 미적용).
 */
@Configuration
public class OpenApiConfig {

    /** BookingController/ReservationController 의 {@code X-Entry-Token} 헤더와 일치해야 한다. */
    static final String ENTRY_TOKEN_SCHEME = "EntryToken";

    @Bean
    public OpenAPI ktxOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("KTX Ticketing API")
                        .version("v1")
                        .description("""
                                KTX 좌석 예매 API. 시연 순서: \
                                ① POST /api/entry 로 EntryToken 발급 → \
                                ② 우측 상단 Authorize 에 토큰 입력 → \
                                ③ POST /api/reservations 예매(SEAT/AUTO) → \
                                ④ confirm/cancel."""))
                .components(new io.swagger.v3.oas.models.Components()
                        .addSecuritySchemes(ENTRY_TOKEN_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Entry-Token")
                                .description("POST /api/entry 응답으로 받은 입장 토큰")));
    }
}
