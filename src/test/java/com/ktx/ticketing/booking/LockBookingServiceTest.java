package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.ReservationRepository;
import com.ktx.ticketing.domain.SeatInventoryRepository;
import com.ktx.ticketing.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LockBookingServiceTest {

    private RedissonClient redisson;
    private RLock lock;
    private LockBookingService service;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);

        service = new LockBookingService(
                redisson,
                mock(SeatInventoryRepository.class),
                mock(ReservationRepository.class),
                mock(UserRepository.class)
        );
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
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        service.bookSeat(1L, 1L, 42L);

        verify(lock, never()).unlock();
    }
}
