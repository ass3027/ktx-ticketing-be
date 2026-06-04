package com.ktx.ticketing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
public class KtxTicketingApplication {

    public static void main(String[] args) {
        SpringApplication.run(KtxTicketingApplication.class, args);
    }

    /** 시간 의존 로직(HELD TTL 등)의 테스트 결정성을 위해 Clock을 주입 가능하게 빈으로 등록. */
    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
