package com.ktx.ticketing.booking;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 예매 엔드포인트({@link BookingController})의 <b>동시 좌석 경합 패배</b>를 409 로 매핑한다
 * (T4-9, T3-8/T3-11 이 미룬 매핑 부채 해소).
 *
 * <p>같은 좌석에 동시 예매가 몰리면 경합 패배가 <b>두 가지 형태</b>로 나타나는데, 둘 다 "이미 다른 요청이
 * 좌석을 잡았다"는 <b>경쟁 패배</b>이지 서버 오류가 아니므로 {@link BookingResult.SeatTaken} 과 동일하게
 * <b>409 CONFLICT</b> 로 응답한다:
 * <ul>
 *   <li>{@link OptimisticLockingFailureException} — 두 요청이 같은 version 을 읽고 동시에 UPDATE(@Version 충돌).</li>
 *   <li>{@link DataIntegrityViolationException} — 승자 커밋 <i>후</i> version 을 다시 읽은 요청이 낙관락은 통과하나
 *       2번째 활성 예약 INSERT 에서 {@code uk_active_seat}(좌석당 활성 1건) 유니크 제약에 걸림(오버셀 DB 최후 방어선).</li>
 * </ul>
 * 선점(SREM) on 이면 앞단에서 대부분 걸러져 드물게만 발생하고, 선점 off(E1 Before)면 이 두 경로가
 * 유일한 방어선이 된다 — 어느 쪽이든 정확성(오버셀 0)은 유지되고 응답만 일관되게 409 가 된다.
 *
 * <p><b>왜 advice 인가</b>: 예외는 트랜잭션 커밋(flush) 경계에서 발생해 tx 가 rollback-only 로 마킹된다.
 * 서비스 안에서 catch 해 정상값을 반환하면 커밋 단계에서 {@code UnexpectedRollbackException}(다시 500)이 된다.
 * advice 는 예외를 전파시켜 tx 를 자연 롤백한 뒤 HTTP 로만 매핑하므로 이 함정을 피한다.
 *
 * <p><b>왜 BookingController 로 스코핑하나</b>: 예매 엔드포인트에서 동시 경합이 유발하는 무결성 위반은
 * {@code uk_active_seat} 뿐이라 {@code DataIntegrityViolationException}=좌석 점유로 단정할 수 있다. 다른
 * 엔드포인트의 무결성 오류(진짜 500)까지 409 로 오매핑하지 않도록 이 advice 는 예매 컨트롤러에만 적용한다.
 */
@RestControllerAdvice(assignableTypes = BookingController.class)
public class BookingExceptionHandler {

    @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ResponseEntity<Void> handleSeatContention(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
