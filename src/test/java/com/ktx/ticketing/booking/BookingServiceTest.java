package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.ReservationRepository;
import com.ktx.ticketing.domain.SeatInventoryRepository;
import com.ktx.ticketing.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BookingServiceTest {

    private SeatPreemptionService preemption;
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        preemption = mock(SeatPreemptionService.class);
        bookingService = new BookingService(
                preemption,
                mock(SeatInventoryRepository.class),
                mock(ReservationRepository.class),
                mock(UserRepository.class)
        );
    }

    @Test
    void bookSeat_선점_실패시_null_반환() {
        when(preemption.tryPreemptSeat(1L, 42L)).thenReturn(false);

        var result = bookingService.bookSeat(1L, 1L, 42L);

        assertThat(result).isNull();
    }

    @Test
    void bookAuto_잔여석_없으면_null_반환() {
        when(preemption.popAnySeat(1L)).thenReturn(null);

        var result = bookingService.bookAuto(1L, 1L);

        assertThat(result).isNull();
    }
}
