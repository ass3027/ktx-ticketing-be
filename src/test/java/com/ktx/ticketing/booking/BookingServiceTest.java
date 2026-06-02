package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock SeatPreemptionService preemption;
    @Mock SeatInventoryRepository seatInventoryRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock UserRepository userRepository;

    @InjectMocks
    BookingService bookingService;

    @Test
    void bookSeat_선점_성공시_HELD_예약_반환() {
        SeatInventory inventory = mock(SeatInventory.class);
        when(preemption.tryPreemptSeat(1L, 42L)).thenReturn(true);
        when(userRepository.getReferenceById(1L)).thenReturn(new User("test@ktx.com", "홍길동"));
        when(seatInventoryRepository.findById(42L)).thenReturn(Optional.of(inventory));

        Reservation result = bookingService.bookSeat(1L, 1L, 42L);

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.HELD);
        verify(inventory).hold(any());
        verify(reservationRepository).save(result);
    }

    @Test
    void bookSeat_선점_실패시_null_반환() {
        when(preemption.tryPreemptSeat(1L, 42L)).thenReturn(false);

        assertThat(bookingService.bookSeat(1L, 1L, 42L)).isNull();
        verifyNoInteractions(reservationRepository);
    }

    @Test
    void bookAuto_자동배정_성공시_HELD_예약_반환() {
        SeatInventory inventory = mock(SeatInventory.class);
        when(preemption.popAnySeat(1L)).thenReturn(42L);
        when(userRepository.getReferenceById(1L)).thenReturn(new User("test@ktx.com", "홍길동"));
        when(seatInventoryRepository.findById(42L)).thenReturn(Optional.of(inventory));

        Reservation result = bookingService.bookAuto(1L, 1L);

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.HELD);
        verify(inventory).hold(any());
        verify(reservationRepository).save(result);
    }

    @Test
    void bookAuto_잔여석_없으면_null_반환() {
        when(preemption.popAnySeat(1L)).thenReturn(null);

        assertThat(bookingService.bookAuto(1L, 1L)).isNull();
        verifyNoInteractions(reservationRepository);
    }
}
