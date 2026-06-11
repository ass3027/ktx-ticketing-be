package com.ktx.ticketing.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트용 MySQL/Redis Testcontainers 를 <b>Spring 빈으로</b> 정의한다.
 *
 * <p><b>왜 {@code @Bean} 인가</b>: Spring Boot 레퍼런스는 컨테이너를 빈으로 두길 권장한다.
 * 컨테이너 lifecycle 이 컨텍스트에 묶여 <em>앱 빈 생성 전 start, 앱 빈 파괴 후 stop</em> 순서가
 * 보장되기 때문이다. JUnit {@code @Testcontainers}+{@code @Container}(static) 는 클래스 종료 시
 * 컨테이너를 stop 하지만 컨텍스트 캐시는 더 오래 살아, 캐시를 재사용하는 다음 클래스가
 * 죽은 컨테이너에 붙어 {@code ConnectException} 이 난다(레퍼런스가 명시적으로 경고하는 케이스).
 * 빈 방식은 컨테이너 stop 시점을 Spring 이 컨텍스트 종료에 맞춰 처리해 이 mismatch 를 없앤다.
 *
 * <p>동일 설정({@code @SpringBootTest @ActiveProfiles("test")})을 공유하는 통합 테스트는 컨텍스트
 * 캐시 1개를 재사용하므로 컨테이너도 1세트만 뜬다. 설정이 다른 테스트가 생기면 별도 컨텍스트가
 * 새 컨테이너를 띄우되, lifecycle 은 각 컨텍스트가 안전하게 관리한다.
 *
 * <p>{@code @ServiceConnection} 이 {@code spring.datasource.*} / {@code spring.data.redis.*} 를
 * 컨테이너 주소로 오버라이드한다. {@link GenericContainer} 는 이미지에서 서비스 종류를 못 추론하므로
 * Redis 는 {@code name = "redis"} 로 connection-details 팩토리를 지정한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContainersConfig {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        // @ServiceConnection 은 컨테이너 JDBC URL 로 spring.datasource.url 을 통째로 덮어쓰므로
        // application.yml 의 rewriteBatchedStatements=true 가 무력화된다. 이 플래그가 없으면
        // batchUpdate 가 행별 INSERT round-trip 으로 실행돼 시드가 수백 배 느려진다(60k행 = 60k 왕복).
        // withUrlParam 으로 컨테이너 URL 에 직접 주입해 batch 가 multi-row INSERT 로 재작성되게 한다.
        return new MySQLContainer(DockerImageName.parse("mysql:8.0"))
                .withUrlParam("rewriteBatchedStatements", "true");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
    }
}
