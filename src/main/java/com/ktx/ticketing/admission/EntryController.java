package com.ktx.ticketing.admission;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 입장 토큰 발급 API (T3-3~5). 활성자 {@code < K} 면 201 + 토큰, {@code ≥ K} 면 429 + Retry-After.
 * 대기 순번은 노출하지 않는다(비가시 입장 제어).
 */
@RestController
@RequestMapping("/api/entry")
@RequiredArgsConstructor
public class EntryController {

    private final AdmissionService admissionService;

    public record EntryRequest(Long scheduleId, Long userId) {}
    public record EntryTokenResponse(String token) {}

    @PostMapping
    public ResponseEntity<?> enter(@RequestBody EntryRequest request) {
        AdmissionResult result = admissionService.tryEnter(request.scheduleId(), request.userId());
        return switch (result) {
            case AdmissionResult.Admitted a -> ResponseEntity.status(HttpStatus.CREATED)
                    .body(new EntryTokenResponse(a.token().value()));
            case AdmissionResult.Rejected r -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(r.retryAfter().toSeconds()))
                    .build();
        };
    }
}
