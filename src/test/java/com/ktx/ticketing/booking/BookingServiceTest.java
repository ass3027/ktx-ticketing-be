package com.ktx.ticketing.booking;

import com.ktx.ticketing.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    /** 고정 시각 — now(clock) 결정성 확보로 5분 HELD TTL을 정확히 단언한다. */
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 7, 1, 8, 0);

    // userId / scheduleId / seatInventoryId 를 서로 다른 값으로 둬서 인자 전치 버그를 잡는다.
    private static final long USER_ID = 7L;
    private static final long SCHEDULE_ID = 1L;
    private static final long SEAT_INVENTORY_ID = 42L;

    @Mock SeatPreemption preemption;
    @Mock SeatInventoryRepository seatInventoryRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock UserRepository userRepository;

    BookingService bookingService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(
                FIXED_NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        bookingService = new BookingService(
                preemption, seatInventoryRepository, reservationRepository, userRepository, fixedClock);
    }

    @Test
    void bookSeat_선점_성공시_HELD_예약을_5분_TTL로_반환() {
        SeatInventory inventory = mock(SeatInventory.class);
        when(preemption.tryPreemptSeat(SCHEDULE_ID, SEAT_INVENTORY_ID)).thenReturn(true);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(new User("test@ktx.com", "홍길동"));
        when(seatInventoryRepository.findById(SEAT_INVENTORY_ID)).thenReturn(Optional.of(inventory));

        Reservation result = bookingService.bookSeat(USER_ID, SCHEDULE_ID, SEAT_INVENTORY_ID);

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.HELD);
        verify(reservationRepository).save(result);

        // Reservation.hold가 좌석을 같은 시각으로 점유 — heldAt=FIXED_NOW, expiresAt은 +HELD_TTL(T1-1)
        verify(inventory).markHeld(FIXED_NOW);
        assertThat(result.getExpiresAt()).isEqualTo(FIXED_NOW.plus(Reservation.HELD_TTL));
    }

    @Test
    void bookAuto_자동배정_성공시_HELD_예약을_5분_TTL로_반환() {
        SeatInventory inventory = mock(SeatInventory.class);
        when(preemption.popAnySeat(SCHEDULE_ID)).thenReturn(SEAT_INVENTORY_ID);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(new User("test@ktx.com", "홍길동"));
        when(seatInventoryRepository.findById(SEAT_INVENTORY_ID)).thenReturn(Optional.of(inventory));

        Reservation result = bookingService.bookAuto(USER_ID, SCHEDULE_ID);

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.HELD);
        verify(reservationRepository).save(result);

        verify(inventory).markHeld(FIXED_NOW);
        assertThat(result.getExpiresAt()).isEqualTo(FIXED_NOW.plus(Reservation.HELD_TTL));
    }

    @Test
    void bookSeat_선점_실패시_null_반환하고_부작용_없음() {
        when(preemption.tryPreemptSeat(SCHEDULE_ID, SEAT_INVENTORY_ID)).thenReturn(false);

        assertThat(bookingService.bookSeat(USER_ID, SCHEDULE_ID, SEAT_INVENTORY_ID)).isNull();
        verifyNoInteractions(reservationRepository, seatInventoryRepository, userRepository);
    }

    @Test
    void bookAuto_잔여석_없으면_null_반환하고_부작용_없음() {
        when(preemption.popAnySeat(SCHEDULE_ID)).thenReturn(null);

        assertThat(bookingService.bookAuto(USER_ID, SCHEDULE_ID)).isNull();
        verifyNoInteractions(reservationRepository, seatInventoryRepository, userRepository);
    }

    @Test
    void bookSeat_선점은_성공했으나_DB에_좌석이_없으면_예외() {
        // Redis 선점(avail Set)과 DB가 어긋난 드리프트 상황 — reconcile(T3-10)이 다루는 케이스.
        when(preemption.tryPreemptSeat(SCHEDULE_ID, SEAT_INVENTORY_ID)).thenReturn(true);
        when(seatInventoryRepository.findById(SEAT_INVENTORY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.bookSeat(USER_ID, SCHEDULE_ID, SEAT_INVENTORY_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(SEAT_INVENTORY_ID)); // 어떤 좌석인지 식별값 노출
        verify(reservationRepository, never()).save(any());
    }
}
