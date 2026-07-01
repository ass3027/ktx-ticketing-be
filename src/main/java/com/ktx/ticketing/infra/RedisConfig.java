package com.ktx.ticketing.infra;

import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 데이터 경로(조회 SCARD·선점 SREM/SPOP·입장 INCR·토큰)를 Lettuce 로 고정하고 Micrometer 로 계측한다.
 *
 * <p><b>왜 명시 구성인가:</b> redisson-spring-boot-starter 는 {@code @ConditionalOnMissingBean(RedisConnectionFactory)}
 * 로 {@code RedissonConnectionFactory} 를 등록해 {@code StringRedisTemplate} 이 Redisson 경로로 나가게 만든다.
 * 그러면 Lettuce 를 안 써서 {@link ClientResources} 계측이 우회되고 {@code lettuce_command_*} 미터가 안 생긴다.
 * 여기서 {@link LettuceConnectionFactory} 를 <b>명시 등록</b>하면 Redisson 의 조건이 깨져 그쪽 factory 는
 * 생성되지 않고(충돌 회피), 데이터 경로 전체가 Lettuce 로 흘러 명령별 지연이 계측된다.
 * <b>분산 락은 {@code RedissonClient} 를 직접 쓰므로 factory 와 무관하게 그대로 유지</b>된다.
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

    /**
     * Lettuce factory 를 명시 등록해 Redisson 의 connection factory 자동등록을 선점한다(§충돌 회피).
     *
     * <p>host/port 는 {@link DataRedisConnectionDetails} 에서 얻는다. 프로덕션은
     * {@code spring.data.redis.*}(env REDIS_HOST/PORT) 기반 details 가, <b>테스트는
     * {@code @ServiceConnection} 이 Testcontainers 동적 주소로 주입한 details</b> 가 온다.
     * (프로퍼티 {@code @Value} 로 직접 읽으면 {@code @ServiceConnection} 은 프로퍼티를 설정하지 않고
     * details 빈으로만 주입하므로 동적 포트를 놓쳐 엉뚱한 Redis 에 붙는다 — 실측 확인.)
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(ClientResources clientResources,
                                                           DataRedisConnectionDetails details) {
        var node = details.getStandalone();
        var standalone = new RedisStandaloneConfiguration(node.getHost(), node.getPort());
        var clientConfig = LettuceClientConfiguration.builder()
                .clientResources(clientResources) // Micrometer recorder 연결 → 계측 활성
                .build();
        return new LettuceConnectionFactory(standalone, clientConfig);
    }

    /** Redisson 의 stringRedisTemplate 자동등록보다 우선(@Primary) — 데이터 경로가 Lettuce factory 를 물게 한다. */
    @Bean
    @Primary
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
