package com.ktx.ticketing.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.time.ZoneId;

/**
 * 테스트 전용 Clock 설정. {@link MutableClock}을 {@code @Primary}로 등록해
 * 앱의 {@code Clock} 빈을 오버라이드한다. {@code @Import(TestClockConfig.class)} 후
 * {@code @Autowired MutableClock}으로 주입받아 시각을 제어한다.
 *
 * <p>Zone 은 {@code systemDefault} — expiresAt(naive LocalDateTime)이 systemDefault 기준으로
 * 저장되므로 UTC 고정 시 KST 9h skew 가 생겨 만료 비교가 어긋난다.
 */
@TestConfiguration
public class TestClockConfig {

    public static final Instant START = Instant.parse("2026-06-09T00:00:00Z");

    @Bean
    @Primary
    public MutableClock clock() {
        return new MutableClock(START, ZoneId.systemDefault());
    }
}
