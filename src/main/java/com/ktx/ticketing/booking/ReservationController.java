package com.ktx.ticketing.booking;

import com.ktx.ticketing.admission.EntrySession;
import com.ktx.ticketing.admission.EntryTokenStore;
import com.ktx.ticketing.domain.Reservation;
import com.ktx.ticketing.domain.ReservationStatus;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예약 생명주기 API (T3-8) — 확정/취소. 자원이 "기존 예약"이라 예매 생성({@link BookingController})과 분리한다.
 *
 * <p>BookingController 와 동일한 토큰 게이트를 쓰되 userId 는 토큰(EntrySession)에서만 취해 서비스의
 * 소유권 검증에 넘긴다(신뢰 경계 — path 의 id 로 남의 예약을 건드리지 못하게 서비스가 막는다). 성공 시
 * 세션이 끝나므로 EntryToken 을 회수(revoke)한다. 활성 슬롯 반환(leave)은 서비스가 커밋 후 수행한다.
 */
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    static final String ENTRY_TOKEN_HEADER = "X-Entry-Token";

    private final EntryTokenStore tokenStore;
    private final ReservationLifecycleService lifecycleService;

    public record ConfirmResponse(Long reservationId, ReservationStatus status) {}

    @PostMapping("/{id}/confirm")
    public ResponseEntity<?> confirm(
            @RequestHeader(value = ENTRY_TOKEN_HEADER, required = false) @Nullable String entryToken,
            @PathVariable Long id) {

        EntrySession session = resolve(entryToken);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        ReservationCommandResult result = lifecycleService.confirm(id, session.userId());
        if (result instanceof ReservationCommandResult.Success s) {
            tokenStore.revoke(entryToken); // 세션 종료 → 토큰 회수
            Reservation r = s.reservation();
            return ResponseEntity.ok(new ConfirmResponse(r.getId(), r.getStatus()));
        }
        return toErrorResponse(result);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(
            @RequestHeader(value = ENTRY_TOKEN_HEADER, required = false) @Nullable String entryToken,
            @PathVariable Long id) {

        EntrySession session = resolve(entryToken);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        ReservationCommandResult result = lifecycleService.cancel(id, session.userId());
        if (result instanceof ReservationCommandResult.Success) {
            tokenStore.revoke(entryToken);
            return ResponseEntity.noContent().build(); // 204
        }
        return toErrorResponse(result);
    }

    private @Nullable EntrySession resolve(@Nullable String entryToken) {
        return entryToken == null ? null : tokenStore.resolve(entryToken); // 누락/만료/무효 → null
    }

    /** Success 가 아닌 결과의 HTTP 매핑. exhaustive switch 로 매핑 누락을 컴파일러가 잡는다. */
    private static ResponseEntity<Void> toErrorResponse(ReservationCommandResult result) {
        HttpStatus status = switch (result) {
            case ReservationCommandResult.NotFound ignored -> HttpStatus.NOT_FOUND;     // 404
            case ReservationCommandResult.Forbidden ignored -> HttpStatus.FORBIDDEN;    // 403
            case ReservationCommandResult.IllegalState ignored -> HttpStatus.CONFLICT;  // 409
            case ReservationCommandResult.Success ignored ->
                    throw new IllegalStateException("Success 는 호출 측에서 이미 처리됨");
        };
        return ResponseEntity.status(status).build();
    }
}
