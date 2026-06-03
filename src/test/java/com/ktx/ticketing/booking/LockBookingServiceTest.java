package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.Reservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LockBookingServiceTest {

    @Mock private RedissonClient redisson;
    @Mock private RLock lock;
    @Mock private BookingTransactionHelper txHelper;
    @InjectMocks
    private LockBookingService service;

    @BeforeEach
    void setUp() {
        when(redisson.getLock(anyString())).thenReturn(lock);
    }

    @Test
    void bookSeat_락_획득_실패시_null_반환() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        var result = service.bookSeat(1L, 1L, 42L);

        assertThat(result).isNull();
    }

    @Test
    void bookAuto_락_획득_실패시_null_반환() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        var result = service.bookAuto(1L, 1L);

        assertThat(result).isNull();
    }

    @Test
    void bookSeat_락_획득_실패시_unlock_호출_안함() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        service.bookSeat(1L, 1L, 42L);

        verify(lock, never()).unlock();
    }

    @Test
    void bookSeat_락_획득_성공시_txHelper_위임결과를_반환하고_락_해제() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        Reservation expected = mock(Reservation.class);
        when(txHelper.holdSeat(1L, 42L)).thenReturn(expected);

        var result = service.bookSeat(1L, 1L, 42L);

        assertThat(result).isSameAs(expected);
        verify(lock).unlock();
    }

    @Test
    void bookAuto_락_획득_성공시_txHelper_위임결과를_반환하고_락_해제() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        Reservation expected = mock(Reservation.class);
        when(txHelper.holdAnySeat(1L, 7L)).thenReturn(expected);

        var result = service.bookAuto(1L, 7L);

        assertThat(result).isSameAs(expected);
        verify(lock).unlock();
    }

    @Test
    void bookSeat_좌석_이미_점유시_null_반환하고_락은_해제() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(txHelper.holdSeat(anyLong(), anyLong())).thenReturn(null);

        var result = service.bookSeat(1L, 1L, 42L);

        assertThat(result).isNull();
        verify(lock).unlock();
    }

    @Test
    void bookSeat_인터럽트시_null_반환하고_인터럽트_상태_복원() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException());

        var result = service.bookSeat(1L, 1L, 42L);

        assertThat(result).isNull();
        assertThat(Thread.interrupted()).isTrue(); // 상태 확인 + 즉시 클리어(다른 테스트 오염 방지)
    }
}
