package com.ktx.ticketing.infra;

import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 분산 락 추상화. 락 라이브러리(Redisson 등)에 대한 결합을 호출 측에서 분리한다.
 *
 * <p>락 획득 → 작업 실행 → 해제의 생명주기와 인터럽트 처리를 구현체가 캡슐화하므로,
 * 호출 측은 {@code try/finally}·{@code tryLock}·{@code unlock} 같은 락 관리 코드를 다루지 않는다.
 *
 * <p>이 인터페이스는 E6(T4-11) 실험에서 Redisson 외 구현체(Spring Integration RedisLockRegistry,
 * 직접 구현 Lettuce 락, ZooKeeper Curator 등)를 교체 투입하기 위한 SPI 역할도 겸한다.
 */
public interface DistributedLock {

    /**
     * {@code key} 에 대한 락을 획득하면 {@code action} 을 실행하고 그 결과를 반환한 뒤 락을 해제한다.
     * 락 획득에 실패하거나 대기 중 인터럽트되면 {@code action} 을 실행하지 않고 {@code null} 을 반환한다.
     * (인터럽트 시 현재 스레드의 인터럽트 상태를 복원한다.)
     *
     * <p>반환값은 {@code action} 의 반환값을 그대로 전달하므로, {@code action} 자체가 {@code null} 을
     * 반환할 수도 있다(예: 선점 패배·잔여석 없음). 즉 {@code null} 은 "락 미획득" 과 "작업 결과 없음"
     * 양쪽을 의미한다.
     *
     * @param key    락 식별 키. 구현체가 자체 prefix/네임스페이스를 덧붙일 수 있다.
     * @param action 락 보호 구간에서 실행할 작업.
     * @param <T>    작업 결과 타입(널 가능).
     */
    <T extends @Nullable Object> T executeWithLock(String key, Supplier<T> action);
}
