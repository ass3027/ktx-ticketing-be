package com.ktx.ticketing.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.time.ZoneId;

/**
 * 테스트 전용 Clock 설정. {@link MutableClock}을 {@code @Primary}로 등록해 {@code Clock} 타입 주입
 * 지점이 앱의 기본 {@code clock} 빈 대신 이걸 받게 한다. {@code @Import(TestClockConfig.class)} 후
 * {@code @Autowired MutableClock}으로 주입받아 시각을 제어한다.
 *
 * <p>빈 이름을 {@code testClock} 으로 분리한다. 앱의 {@code KtxTicketingApplication#clock()} 도
 * 빈 이름이 {@code clock} 이라 메서드명을 그대로 두면 동일 이름으로 정의가 두 번 등록돼
 * {@code BeanDefinitionOverrideException} 이 난다(Spring Boot 기본 {@code allow-bean-definition-overriding=false}).
 * {@code @Primary} 는 다중 후보 중 우선순위 신호일 뿐 오버라이드 허가가 아니다.
 *
 * <p>Zone 은 {@code systemDefault} — expiresAt(naive LocalDateTime)이 systemDefault 기준으로
 * 저장되므로 UTC 고정 시 KST 9h skew 가 생겨 만료 비교가 어긋난다.
 */
@TestConfiguration
public class TestClockConfig {

    public static final Instant START = Instant.parse("2026-06-09T00:00:00Z");

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(START, ZoneId.systemDefault());
    }
}
