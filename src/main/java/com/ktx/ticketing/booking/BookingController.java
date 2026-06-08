package com.ktx.ticketing.booking;

import com.ktx.ticketing.admission.EntrySession;
import com.ktx.ticketing.admission.EntryTokenStore;
import com.ktx.ticketing.domain.Reservation;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 예매 API (T3-6/7). EntryToken 검증 후 SEAT/AUTO 예매.
 *
 * <p>신뢰 경계: userId·scheduleId 는 <b>토큰(EntrySession)에서만</b> 취한다. 본문은 mode 와
 * (SEAT 일 때) seatInventoryId 만 받아, body 의 위변조된 식별자로 남의 세션을 예매하는 길을 막는다.
 * 토큰 검증은 컨트롤러의 얇은 게이트로 두고, 예매는 {@link BookingService} 에 위임한다.
 */
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class BookingController {

    static final String ENTRY_TOKEN_HEADER = "X-Entry-Token";

    private final EntryTokenStore tokenStore;
    private final BookingService bookingService;

    public record ReservationRequest(BookingMode mode, @Nullable Long seatInventoryId) {}
    public record ReservationResponse(Long reservationId, LocalDateTime expiresAt) {}

    @PostMapping
    public ResponseEntity<?> reserve(
            @RequestHeader(value = ENTRY_TOKEN_HEADER, required = false) @Nullable String entryToken,
            @RequestBody ReservationRequest request) {

        if (entryToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        EntrySession session = tokenStore.resolve(entryToken);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build(); // 누락/만료/무효 토큰
        }
        if (request.mode() == BookingMode.SEAT && request.seatInventoryId() == null) {
            return ResponseEntity.badRequest().build(); // SEAT 인데 좌석 미지정
        }

        BookingResult result = book(request, session);
        return toResponse(result);
    }

    private BookingResult book(ReservationRequest request, EntrySession session) {
        return switch (request.mode()) {
            case SEAT -> bookingService.bookSeat(
                    session.userId(), session.scheduleId(), request.seatInventoryId());
            case AUTO -> bookingService.bookAuto(session.userId(), session.scheduleId());
        };
    }

    private static ResponseEntity<?> toResponse(BookingResult result) {
        return switch (result) {
            case BookingResult.Success s -> {
                Reservation r = s.reservation();
                yield ResponseEntity.status(HttpStatus.CREATED)
                        .body(new ReservationResponse(r.getId(), r.getExpiresAt()));
            }
            case BookingResult.SeatTaken ignored -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case BookingResult.SoldOut ignored -> ResponseEntity.status(HttpStatus.GONE).build();
            // Overloaded 는 입장 제어에서 이미 걸러져 예매 단계엔 도달하지 않는다 — exhaustive switch 를 위한 방어 분기.
            case BookingResult.Overloaded o -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", String.valueOf(o.retryAfterSeconds())).build();
        };
    }
}
