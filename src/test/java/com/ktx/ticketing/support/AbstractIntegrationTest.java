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
 *       {@code spring.data.redis.*} 를 컨테이너 주소로 오버라이드한다. Redisson 스타터도 동일한
 *       {@code spring.data.redis.*} 를 소비하므로 분산락 경로도 컨테이너를 바라본다.</li>
 *   <li>{@code @ActiveProfiles("test")} 이 {@code application-test.yml} 을 활성화한다 — 메인
 *       {@code application.yml} 을 base 로 상속하고 {@code ddl-auto: create-drop}(컨텍스트마다 빈 스키마),
 *       {@code booking.scheduler.enabled=false}(배경 sweep/reconcile 정지)만 오버라이드한다.</li>
 *   <li>시드는 {@code @Profile("local")} 의 {@code DataInitializer} 가 아니라 <b>각 통합 테스트가 직접</b>
 *       INSERT 한다(test 프로파일에선 DataInitializer 가 돌지 않음). 정합성 테스트는 좌석 소수만 필요하므로
 *       대용량 시드(50k)를 끌어들이지 않는 자급자족 방식이 더 결정적이다.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ContainersConfig.class)
public abstract class AbstractIntegrationTest {
}
