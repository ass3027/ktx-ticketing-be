package com.ktx.ticketing.infra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RedissonDistributedLock 은 락 획득→작업 실행→해제 생명주기와 인터럽트 처리를 캡슐화한다.
 * RLock 자체 동작은 Redisson 의 책임이므로 모킹하고, 이 구현체가 책임지는
 * "획득 성공/실패·인터럽트 분기, 보유 시에만 해제, 키 prefix" 만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RedissonDistributedLockTest {

    @Mock private RedissonClient redisson;
    @Mock private RLock rLock;
    @InjectMocks private RedissonDistributedLock lock;

    @BeforeEach
    void setUp() {
        when(redisson.getLock(anyString())).thenReturn(rLock);
    }

    @Test
    void 락_획득_성공시_action_실행결과를_반환하고_해제() throws InterruptedException {
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        var result = lock.executeWithLock("schedule:1", () -> "ok");

        assertThat(result).isEqualTo("ok");
        verify(rLock).unlock();
    }

    @Test
    void 락_획득_성공후_action이_null을_반환해도_정상_해제() throws InterruptedException {
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        var result = lock.executeWithLock("schedule:1", () -> null);

        assertThat(result).isNull();
        verify(rLock).unlock();
    }

    @Test
    void 락_미획득시_action을_실행하지_않고_null_반환하며_해제도_안함() throws InterruptedException {
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        AtomicBoolean actionRan = new AtomicBoolean(false);

        var result = lock.executeWithLock("schedule:1", () -> {
            actionRan.set(true);
            return "x";
        });

        assertThat(result).isNull();
        assertThat(actionRan).isFalse();
        verify(rLock, never()).unlock();
    }

    @Test
    void 대기중_인터럽트시_null_반환하고_인터럽트_상태_복원() throws InterruptedException {
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException());

        var result = lock.executeWithLock("schedule:1", () -> "x");

        assertThat(result).isNull();
        assertThat(Thread.interrupted()).isTrue(); // 상태 확인 + 즉시 클리어(다른 테스트 오염 방지)
    }

    @Test
    void 키에_lock_prefix를_붙여_분산락을_조회() {
        // tryLock 미스텁 → 기본 false 반환(미획득). 조회 키만 검증.
        lock.executeWithLock("schedule:1", () -> "x");

        verify(redisson).getLock("lock:schedule:1");
    }
}
