# P3 기능 구현 결과 (M3 진행 중)

> 출처: `KTX_Ticketing_Task_Checklist.md`
> DoD(M3): 정상 16단계 E2E + 예외 6종 처리
> 사용법: P3 태스크가 완료될 때마다 본 문서에 섹션을 누적한다.

---

## 진행 현황

| 태스크 | 상태 | 비고 |
|--------|------|------|
| T3-5b 예매 결과 반환 타입 결정 | ✅ | sealed `BookingResult` 채택 |
| T3-1 운행 리스트 조회 API | ⬜ | |
| T3-2 매진/잔여석 표시 | ⬜ | |
| T3-3 입장 제어(상한 K) | ⬜ | |
| T3-4 초과 시 429/503 + Retry-After | ⬜ | |
| T3-5 EntryToken 발급/만료/검증 | ⬜ | |
| T3-6 예매 API `mode=SEAT` | ⬜ | |
| T3-7 예매 API `mode=AUTO` | ⬜ | |
| T3-8 결제 확정/취소 + 카운터 동기화 | ⬜ | |
| T3-9 HELD TTL 만료 스케줄러 | ⬜ | |
| T3-9b 시간 소스(Clock) 단일화 | ⬜ | |
| T3-10 Redis-DB reconcile 잡 | ⬜ | |
| T3-11 통합 테스트(정합성 자동화) | ⬜ | |

---

## T3-5b — 예매 결과 반환 타입 결정 (2026-06-04)

### 결정
공개 예매 진입점의 `@Nullable Reservation` 반환을 **sealed `BookingResult`** 로 교체.

```java
public sealed interface BookingResult {
    record Success(Reservation reservation) implements BookingResult {}
    record SeatTaken() implements BookingResult {}              // SEAT 선점 패배 → 409
    record SoldOut() implements BookingResult {}                // AUTO 잔여석 없음 → 410
    record Overloaded(long retryAfterSeconds) implements BookingResult {} // 입장 초과 → 503
}
```

### 근거 (트레이드오프)
| 비교 | 채택안 (`BookingResult`) | 대안 (`null` / 예외) |
|------|--------------------------|----------------------|
| 사유 구분 | 변형으로 명시 (SeatTaken≠SoldOut) | `null` 은 두 사유를 뭉갬 |
| 컨트롤러 매핑 | exhaustive `switch` → 누락을 **컴파일러가 검출** | if/else, 분기 누락 런타임까지 잠복 |
| 비용 | 경쟁 패배 = 1,000 요청 중 다수 = **정상 흐름**, 값으로 표현 | 예외는 스택트레이스 생성 비용 + 의미상 부적합 |

→ 경쟁 패배가 예외가 아니라 정상 흐름의 다수 케이스라는 점이 핵심. 값(sealed)으로 표현하는 것이 의미·비용 양면에서 타당.

### 변경 범위
| 파일 | 변경 |
|------|------|
| `booking/BookingResult.java` | **신규** — sealed interface + 4 record, 변형별 HTTP 매핑 Javadoc |
| `booking/BookingService.java` | `bookSeat`/`bookAuto` → `BookingResult` 반환, `@Nullable` import 제거 |
| `booking/LockBookingService.java` | 동일 반환 전환 + 미사용 import(`RLock`/`RedissonClient`/`Nullable`) 정리. 락 미획득도 SEAT→`SeatTaken`, AUTO→`SoldOut` 로 매핑 |
| `booking/BookingTransactionHelper.java` | **무변경** — 트랜잭션 경계 내부 헬퍼라 `@Nullable Reservation` 유지, 매핑은 호출 측 책임 |
| `booking/package-info.java` | `@Nullable` 문구를 `BookingResult` 체계에 맞게 갱신 |

### 설계 판단
- **`BookingTransactionHelper` 는 `@Nullable` 유지**: 락 외부에서 트랜잭션을 여는 저수준 헬퍼. `BookingResult` 변환을 여기까지 내리면 트랜잭션 경계 책임과 표현 책임이 섞인다. 변환은 진입점(`LockBookingService`)에 둔다.
- **`Overloaded` 는 타입만 정의, 발생은 미구현**: 입장 제어(T3-3~5)에서 생성. 컨트롤러 `switch` 를 한 번만 작성하도록 매핑 대상을 미리 고정.
- **E1 비교군 정합**: `BookingService`(Redis 선점)와 `LockBookingService`(Redisson 락)가 동일 `BookingResult` 를 반환 → 컨트롤러가 두 경로를 토글하며 동일하게 처리 가능.

### 테스트
| 클래스 | 변경 | 검증 |
|--------|------|------|
| `BookingServiceTest` | `null` 단언 → 변형 타입 단언 | Success/SeatTaken/SoldOut 반환 |
| `LockBookingServiceTest` | 동일 + `bookAuto` 락 미획득 케이스 **신규** | Success/SeatTaken/SoldOut 반환 |
| `ConcurrencyPocTest` | 성공 판정 `result != null` → **`instanceof Success`** | 반환이 항상 non-null 이 되며 기존 판정이 전건 성공으로 오작동 — 발견·수정 |

### 검증 결과
- `compileJava` / `compileTestJava` ✅
- `BookingServiceTest`, `LockBookingServiceTest` ✅
- `ConcurrencyPocTest` ✅ — 1,000 동시요청 **oversell=0 / 중복 예약=0 유지** (Testcontainers, 45s)
