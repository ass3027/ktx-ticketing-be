package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.Reservation;
import com.ktx.ticketing.infra.DistributedLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * LockBookingService 는 Redisson 을 직접 다루지 않고 {@link DistributedLock} 에 위임한다.
 * 따라서 이 단위 테스트는 락 생명주기가 아니라 "스케줄 단위 키로 위임하고, 락 보호 구간에서
 * 올바른 txHelper 메서드를 호출해 그 결과를 그대로 반환하는가" 만 검증한다.
 * (락 획득/해제/인터럽트 동작은 RedissonDistributedLockTest 의 책임)
 */
@ExtendWith(MockitoExtension.class)
class LockBookingServiceTest {

    @Mock private DistributedLock lock;
    @Mock private BookingTransactionHelper txHelper;
    @InjectMocks private LockBookingService service;

    /** 락 획득 성공을 모사: 전달된 action 을 실제로 실행해 그 결과를 돌려준다. */
    private void simulateLockAcquired() {
        when(lock.executeWithLock(anyString(), any()))
                .thenAnswer(inv -> inv.<Supplier<?>>getArgument(1).get());
    }

    @Test
    void bookSeat_스케줄_락_키로_위임하고_지정좌석_점유결과를_Success로_반환() {
        Reservation expected = mock(Reservation.class);
        when(txHelper.holdSeat(1L, 42L)).thenReturn(expected);
        simulateLockAcquired();

        var result = service.bookSeat(1L, 7L, 42L);

        assertThat(result).isInstanceOf(BookingResult.Success.class);
        assertThat(((BookingResult.Success) result).reservation()).isSameAs(expected);
        verify(lock).executeWithLock(eq("schedule:7"), any());
    }

    @Test
    void bookAuto_스케줄_락_키로_위임하고_자동배정_결과를_Success로_반환() {
        Reservation expected = mock(Reservation.class);
        when(txHelper.holdAnySeat(1L, 7L)).thenReturn(expected);
        simulateLockAcquired();

        var result = service.bookAuto(1L, 7L);

        assertThat(result).isInstanceOf(BookingResult.Success.class);
        assertThat(((BookingResult.Success) result).reservation()).isSameAs(expected);
        verify(lock).executeWithLock(eq("schedule:7"), any());
    }

    @Test
    void bookSeat_락_미획득시_좌석점유를_시도하지_않고_SeatTaken_반환() {
        // executeWithLock 이 (락 미획득을 모사하여) action 을 실행하지 않고 null 반환
        when(lock.executeWithLock(anyString(), any())).thenReturn(null);

        var result = service.bookSeat(1L, 7L, 42L);

        assertThat(result).isInstanceOf(BookingResult.SeatTaken.class);
        verifyNoInteractions(txHelper);
    }

    @Test
    void bookAuto_락_미획득시_좌석점유를_시도하지_않고_SoldOut_반환() {
        when(lock.executeWithLock(anyString(), any())).thenReturn(null);

        var result = service.bookAuto(1L, 7L);

        assertThat(result).isInstanceOf(BookingResult.SoldOut.class);
        verifyNoInteractions(txHelper);
    }
}
