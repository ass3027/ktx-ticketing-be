package com.ktx.ticketing.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 통합 테스트 공용 베이스. MySQL/Redis 를 Testcontainers 로 띄워
 * 실제 로컬 인프라(localhost:3306/6379) 의존을 제거한다.
 *
 * <ul>
 *   <li>컨테이너는 {@link ContainersConfig} 가 <b>Spring 빈</b>으로 정의한다. 빈 방식은 컨테이너
 *       lifecycle 을 컨텍스트에 묶어 캐시 재사용 시에도 죽은 컨테이너 재접속({@code ConnectException})을
 *       막는다 — 근거는 {@link ContainersConfig} 주석 참고.</li>
 *   <li>{@code @ServiceConnection}({@code ContainersConfig})이 {@code spring.datasource.*} /
 *       {@code spring.data.redis.*} 를 컨테이너 주소로 오버라이드하므로 {@code application-local.yml} 의
 *       하드코딩 localhost 는 무력화된다. Redisson 스타터도 동일한 {@code spring.data.redis.*} 를
 *       소비하므로 분산락 경로도 컨테이너를 바라본다.</li>
 *   <li>{@code @ActiveProfiles("local")} 이 {@code DataInitializer}(@Profile("local")) 와
 *       {@code ddl-auto: update} 를 활성화한다. ApplicationRunner 는 컨텍스트 refresh(=스키마 생성) 이후
 *       실행되므로 시드 전에 스키마가 준비된다.</li>
 * </ul>
 */
//TODO 이거 굳이 abstract class 로?
@SpringBootTest
@ActiveProfiles("test")
@Import(ContainersConfig.class)
public abstract class AbstractIntegrationTest {
}
