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
        bookingService = newBookingService(true); // 기본: 선점 on(프로덕션)
    }

    /** 선점 토글(T4-9 E1)을 바꿔가며 조립 — enabled=false 는 SREM 우회 경로 검증용. */
    private BookingService newBookingService(boolean preemptionEnabled) {
        Clock fixedClock = Clock.fixed(
                FIXED_NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        return new BookingService(
                preemption, new PreemptionProperties(preemptionEnabled),
                seatInventoryRepository, reservationRepository, userRepository, fixedClock);
    }

    @Test
    void bookSeat_선점_성공시_HELD_예약을_5분_TTL로_반환() {
        SeatInventory inventory = mock(SeatInventory.class);
        when(preemption.tryPreemptSeat(SCHEDULE_ID, SEAT_INVENTORY_ID)).thenReturn(true);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(new User("test@ktx.com", "홍길동"));
        when(seatInventoryRepository.findById(SEAT_INVENTORY_ID)).thenReturn(Optional.of(inventory));

        BookingResult result = bookingService.bookSeat(USER_ID, SCHEDULE_ID, SEAT_INVENTORY_ID);

        assertThat(result).isInstanceOf(BookingResult.Success.class);
        Reservation reservation = ((BookingResult.Success) result).reservation();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
        verify(reservationRepository).save(reservation);

        // Reservation.hold가 좌석을 같은 시각으로 점유 — heldAt=FIXED_NOW, expiresAt은 +HELD_TTL(T1-1)
        verify(inventory).markHeld(FIXED_NOW);
        assertThat(reservation.getExpiresAt()).isEqualTo(FIXED_NOW.plus(Reservation.HELD_TTL));
    }

    @Test
    void bookAuto_자동배정_성공시_HELD_예약을_5분_TTL로_반환() {
        SeatInventory inventory = mock(SeatInventory.class);
        when(preemption.popAnySeat(SCHEDULE_ID)).thenReturn(SEAT_INVENTORY_ID);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(new User("test@ktx.com", "홍길동"));
        when(seatInventoryRepository.findById(SEAT_INVENTORY_ID)).thenReturn(Optional.of(inventory));

        BookingResult result = bookingService.bookAuto(USER_ID, SCHEDULE_ID);

        assertThat(result).isInstanceOf(BookingResult.Success.class);
        Reservation reservation = ((BookingResult.Success) result).reservation();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
        verify(reservationRepository).save(reservation);

        verify(inventory).markHeld(FIXED_NOW);
        assertThat(reservation.getExpiresAt()).isEqualTo(FIXED_NOW.plus(Reservation.HELD_TTL));
    }

    @Test
    void bookSeat_선점_실패시_SeatTaken_반환하고_부작용_없음() {
        when(preemption.tryPreemptSeat(SCHEDULE_ID, SEAT_INVENTORY_ID)).thenReturn(false);

        assertThat(bookingService.bookSeat(USER_ID, SCHEDULE_ID, SEAT_INVENTORY_ID))
                .isInstanceOf(BookingResult.SeatTaken.class);
        verifyNoInteractions(reservationRepository, seatInventoryRepository, userRepository);
    }

    @Test
    void bookAuto_잔여석_없으면_SoldOut_반환하고_부작용_없음() {
        when(preemption.popAnySeat(SCHEDULE_ID)).thenReturn(null);

        assertThat(bookingService.bookAuto(USER_ID, SCHEDULE_ID))
                .isInstanceOf(BookingResult.SoldOut.class);
        verifyNoInteractions(reservationRepository, seatInventoryRepository, userRepository);
    }

    @Test
    void bookSeat_선점_off면_SREM_건너뛰고_바로_HELD_점유() {
        // T4-9 E1 Before: 선점(SREM) 우회 → tryPreemptSeat 호출 없이 DB 상태전이로 직행(@Version 이 최종 방어).
        BookingService noPreemption = newBookingService(false);
        SeatInventory inventory = mock(SeatInventory.class);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(new User("test@ktx.com", "홍길동"));
        when(seatInventoryRepository.findById(SEAT_INVENTORY_ID)).thenReturn(Optional.of(inventory));

        BookingResult result = noPreemption.bookSeat(USER_ID, SCHEDULE_ID, SEAT_INVENTORY_ID);

        assertThat(result).isInstanceOf(BookingResult.Success.class);
        verify(preemption, never()).tryPreemptSeat(any(), any());
        verify(inventory).markHeld(FIXED_NOW);
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
