package com.ktx.ticketing.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트 공용 베이스. MySQL/Redis 를 Testcontainers 로 띄워
 * 실제 로컬 인프라(localhost:3306/6379) 의존을 제거한다.
 *
 * <ul>
 *   <li>{@code static @Container} → 컨테이너는 클래스당 1회 기동, 같은 클래스의 모든 테스트가 공유,
 *       JVM 종료 시 Ryuk 가 정리한다.</li>
 *   <li>{@code @ServiceConnection} 이 {@code spring.datasource.*} / {@code spring.data.redis.*} 를
 *       컨테이너 주소로 오버라이드하므로 {@code application-local.yml} 의 하드코딩 localhost 는 무력화된다.
 *       Redisson 스타터도 동일한 {@code spring.data.redis.*} 를 소비하므로 분산락 경로도 컨테이너를 바라본다.</li>
 *   <li>{@code @ActiveProfiles("local")} 이 {@code DataInitializer}(@Profile("local")) 와
 *       {@code ddl-auto: update} 를 활성화한다. ApplicationRunner 는 컨텍스트 refresh(=스키마 생성) 이후
 *       실행되므로 시드 전에 스키마가 준비된다.</li>
 * </ul>
 *
 * <p>Redis 는 Testcontainers 2.0.x 에 전용 모듈이 없어 코어의 {@link GenericContainer}(redis 이미지)로 띄운다.
 * Spring Boot 의 {@code RedisContainerConnectionDetailsFactory} 가 {@code "redis"} 이미지 패밀리를 인식해
 * {@code @ServiceConnection} 으로 자동 연결한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0"))
                    // @ServiceConnection 은 컨테이너 JDBC URL 로 spring.datasource.url 을 통째로 덮어쓰므로
                    // application.yml 의 rewriteBatchedStatements=true 가 무력화된다. 이 플래그가 없으면
                    // batchUpdate 가 행별 INSERT round-trip 으로 실행돼 시드가 수백 배 느려진다(60k행 = 60k 왕복).
                    // withUrlParam 으로 컨테이너 URL 에 직접 주입해 batch 가 multi-row INSERT 로 재작성되게 한다.
                    .withUrlParam("rewriteBatchedStatements", "true");

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);
}
