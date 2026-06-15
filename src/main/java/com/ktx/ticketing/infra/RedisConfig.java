package com.ktx.ticketing.infra;

import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lettuce Micrometer 계측 활성화 — Redis 명령 지연을 Prometheus 메트릭으로 수집.
 * Spring Boot 자동 구성이 Lettuce 계측을 제공하지 않으므로 ClientResources 를 직접 구성한다.
 */
@Configuration
public class RedisConfig {

    @Bean(destroyMethod = "shutdown")
    public ClientResources clientResources(MeterRegistry meterRegistry) {
        MicrometerCommandLatencyRecorder recorder = new MicrometerCommandLatencyRecorder(
                meterRegistry,
                MicrometerOptions.create()
        );
        return ClientResources.builder()
                .commandLatencyRecorder(recorder)
                .build();
    }
}
