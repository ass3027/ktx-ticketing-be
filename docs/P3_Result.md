# P3 기능 구현 결과 (M3 진행 중)

> 출처: `KTX_Ticketing_Task_Checklist.md`
> DoD(M3): 정상 16단계 E2E + 예외 6종 처리
> 사용법: P3 태스크가 완료될 때마다 본 문서에 섹션을 누적한다.

---

## 진행 현황

| 태스크 | 상태 | 비고 |
|--------|------|------|
| T3-5b 예매 결과 반환 타입 결정 | ✅ | sealed `BookingResult` 채택 |
| T3-1 운행 리스트 조회 API | ✅ | `GET /api/schedules` 커서 페이징, `schedule` 패키지 |
| T3-2 매진/잔여석 표시 | ✅ | `avail:` SCARD 단일 소스, `remainingSeats`/`soldOut` |
| T3-3 입장 제어(상한 K) | ✅ | `active:` INCR-rollback, K 외부화 |
| T3-4 초과 시 429/503 + Retry-After | ✅ | 429 + `Retry-After` 헤더 |
| T3-5 EntryToken 발급/만료/검증 | ✅ | 불투명 UUID + `entry:` TTL, `admission` 패키지 |
| T3-6 예매 API `mode=SEAT` | ✅ | `POST /api/reservations`, SREM 선점, 신뢰 경계 |
| T3-7 예매 API `mode=AUTO` | ✅ | 동일 엔드포인트 mode 분기, SPOP 자동배정 |
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

---

## T3-1 — 운행 리스트 조회 API (2026-06-04)

### 결정
`GET /api/schedules` — 무한 스크롤을 위한 **커서 페이징**. 신규 `com.ktx.ticketing.schedule` 패키지(읽기 기능 단위).

| 항목 | 결정 | 근거 |
|------|------|------|
| 패키지 | `schedule` | `booking`(쓰기)과 동일한 기능 단위 패키징. `query` 는 CQRS 오해·이름 모호로 기각 |
| 조회 모델 | `from + limit` 커서 페이징 | KTX 앱 무한 스크롤의 표준 구현 = 커서. 날짜 구간(`from~to`)은 페이징 별도 필요 + 구간 폭주 위험 |
| 커서 키 | `(departureTime, id)` 복합 | 동일 출발시각 운행편의 누락·중복 방지(안정 정렬) |
| limit | 기본 8 / 최대 100 / 최소 1 | 무한 스크롤 페이지 크기. 상한으로 부하 보호 |

### API
```
GET /api/schedules?dep=서울&arr=부산&from=2026-07-01T09:00&afterId=7&limit=8
```
| 파라미터 | 필수 | 비고 |
|---------|------|------|
| `dep`, `arr` | ✅ | 출발/도착역 |
| `from` | ✅ | ISO `LocalDateTime`. 이 시각 이후 운행편 |
| `afterId` | — | 커서. 미지정 시 0 정규화 → `departureTime >= from` 효과 |
| `limit` | — | 기본 8, 1~100 클램프 |

응답 `{ items: [...], nextCursor: { from, afterId } | null }`.
`nextCursor` 는 `items.size() == limit`(페이지 꽉 참)일 때만 발급, 마지막 페이지면 `null`.

### 변경 범위
| 파일 | 변경 |
|------|------|
| `schedule/package-info.java` | **신규** — `@NullMarked` + 읽기 경로 설명 |
| `schedule/ScheduleResponse.java` | **신규** — 운행편 1건 DTO(`from(Schedule)`). T3-2 에서 잔여석/매진 필드 추가 예정 |
| `schedule/ScheduleListResponse.java` | **신규** — `items` + `nextCursor`(중첩 `Cursor` record) |
| `schedule/ScheduleQueryService.java` | **신규** — limit 클램프·afterId 정규화·nextCursor 계산, 조회는 리포지토리 위임 |
| `schedule/ScheduleController.java` | **신규** — 프로젝트 첫 컨트롤러. `@DateTimeFormat` 으로 `from` 바인딩 |
| `domain/ScheduleRepository.java` | `findPageAfter` JPQL 추가 — `train` fetch join(N+1 회피) + 복합 커서 + `Pageable` |

### 설계 판단
- **잔여석/매진은 이번 범위 제외**: DTO에 자리만 두고 T3-2(Redis 카운터, 약한 일관성)에서 채운다. 태스크 경계 유지.
- **`train` fetch join**: 응답에 `trainNumber`/`name` 이 필요한데 `@ManyToOne(LAZY)` → N+1. 조회 p95 ≤ 200ms 목표상 fetch join 으로 한 번에 로딩.
- **복합 커서 `afterId`**: `departureTime` 단독 커서는 동시각 운행편에서 누락/중복 발생. `id` 를 2차 정렬·커서로 추가해 안정화.

### 테스트 (`ScheduleQueryServiceTest`, Mockito 단위 · 8건)
커서 페이징 **경계 로직**에 집중:
- limit 기본값(8)·상한(100)·하한(1) 클램프
- `afterId` 미지정 시 0 정규화 / 지정 시 그대로 위임
- 페이지 꽉 참 → `nextCursor` = 마지막 항목 `(from, id)`
- 덜 참/빈 결과 → `nextCursor` null

> JPQL 자체(fetch join·복합 커서 실제 동작)는 단위로 못 잡음 → **통합 테스트(T3-11)** 의 책임으로 명시.
> 컨트롤러 바인딩/직렬화는 프레임워크 동작이라 단위 테스트 제외(프로젝트 테스트 규칙).

### 검증 결과
- `compileJava` ✅
- `ScheduleQueryServiceTest` 8건 ✅
- 전체 테스트 ✅ — 회귀 없음(`ConcurrencyPocTest` 포함, 45s)

---

## T3-2 — 매진/잔여석 표시 (2026-06-04)

### 결정
운행편 응답에 `remainingSeats`/`soldOut` 추가. 잔여석 소스는 **`avail:` Set 크기(SCARD) 단일 소스** 재사용 — 별도 `remain:` String 카운터는 도입하지 않음.

| 비교 | 채택 (A: SCARD 재사용) | 기각 (B: `remain:` 별도 카운터) |
|------|------------------------|--------------------------------|
| 소스 수 | **단일** — `avail:` Set이 이미 단일 선점 지점, 그 크기가 곧 잔여 | 이중 — Set + String 둘 다 갱신 필요 |
| 동기화 | 선점(SREM/SPOP)·반환(SADD)이 곧 잔여 갱신, 추가 작업 0 | 예매/취소/만료마다 Set·String 동시 갱신, 어긋나면 reconcile 부담 2배 |
| 성능 | SCARD O(1) | DECR/INCR 미세 우위뿐 |

→ `remain:`(P1 설계 안)은 정합성 위험 대비 이득이 약해 기각. 그 정당성은 E3(T4-3) 측정으로 따진다.

### 매진 정의
`soldOut = (remainingSeats == 0)`. 매진은 **보수적**으로 — 선점 즉시 Set에서 빠지므로 SCARD가 0을 곧바로 반영, false "available" 위험 낮음. "잔여 1석" 보고 들어가 매진은 정상(표시는 안내, 최종 판정은 예매 임계영역).

### 변경 범위
| 파일 | 변경 |
|------|------|
| `schedule/ScheduleResponse.java` | `remainingSeats`/`soldOut` 필드 추가, `from(Schedule, long)` 로 시그니처 변경 |
| `schedule/ScheduleQueryService.java` | `SeatPreemption` 주입, 각 편에 `availableCount(scheduleId)` 덧입힘 |

### 설계 판단
- **패키지 의존 방향**: `schedule` → `booking.SeatPreemption`(SPI 인터페이스, 읽기용 `availableCount` 포함). 단방향이라 순환 없음.
- **SCARD 직렬 루프**: 페이지당 최대 limit(≤100)회. 기본 8건이라 단건 조회 영향 미미. **파이프라인/캐시는 측정 후 E3(T4-3) Before/After 산출물로 미룸** — 조기 최적화 회피 + 측정 기회 보존. 코드에 의도 주석 명시.
- **읽기 전용**: 카운터 갱신(SREM/SADD)은 `RedisSetPreemption`에 이미 존재. T3-2는 표시만.

### 테스트 (`ScheduleQueryServiceTest` 확장, +2건 → 총 10건)
- 각 운행편 잔여석을 `availableCount(id)` 결과로 채우는지
- 잔여 0 → `soldOut=true`, 그 외 → false
- 기존 페이징 경계 테스트는 잔여석 무관 → `SeatPreemption` lenient 기본 stub(0)

### 검증 결과
- `compileJava` ✅
- `ScheduleQueryServiceTest` 10건 ✅
- 전체 테스트 ✅ — 회귀 없음(48s)

---

## T3-3~5 — 입장 제어 (`POST /api/entry`) (2026-06-04)

### 결정
비가시 입장 제어. 활성자 `< K` 면 EntryToken 발급(+1), `≥ K` 면 429 + Retry-After. 신규 `admission` 패키지.

| 결정 | 채택 | 근거 |
|------|------|------|
| 활성자 동시성 | **INCR 반환값으로 판정 → 초과 시 DECR 롤백** | "GET→비교→INCR" 3단계는 비원자라 K 초과 가능. INCR 원자 반환값으로 race 회피. Lua는 근사 일관성엔 과함 |
| EntryToken | **불투명 UUID + Redis 저장**(`entry:{token}`) | 검증=Redis 조회, 만료/회수가 TTL·삭제로 일관. JWT는 즉시 무효화 어려움 |
| 상한 K | **설정 외부화**(`booking.admission.max-active`) | L5 임계점 탐색(T4-7)에서 역산·확정. 하드코딩 금지 — 측정 후 설정만 변경 |
| 초과 응답 | **429 + Retry-After** | 사용자별 재시도 안내(부하 셰딩). 순번 미노출 |
| 결과 타입 | 작은 sealed `AdmissionResult`(Admitted/Rejected) | "토큰 or 초과" 2가지 — 컨트롤러 switch 매핑, 초과는 정상 흐름이라 값으로 표현 |

### API
```
POST /api/entry   body: { scheduleId, userId }
  201 + { token }                       (활성자 < K)
  429 + Retry-After: 5                  (활성자 ≥ K, 순번 없음)
```

### 변경 범위 (`admission` 패키지 신규)
| 파일 | 역할 |
|------|------|
| `package-info.java` | `@NullMarked` + 입장 제어 설명 |
| `AdmissionProperties.java` | `booking.admission.*` 설정 바인딩(maxActive/tokenTtl/retryAfter) |
| `AdmissionService.java` | `tryEnter`(INCR-rollback) + `leave`(슬롯 반환, T3-8/9서 호출) |
| `EntryToken.java` / `EntrySession.java` | 불투명 토큰 / 토큰이 가리키는 (scheduleId,userId) |
| `EntryTokenStore.java` | `entry:{token}` issue/resolve/revoke (Redis, TTL) |
| `AdmissionResult.java` | sealed Admitted(token) / Rejected(retryAfter) |
| `EntryController.java` | `POST /api/entry`, 429 + Retry-After 매핑 |
| `KtxTicketingApplication.java` | `@ConfigurationPropertiesScan` 추가 |
| `application.yml`(+ test) | admission 설정값(max-active=100 잠정) |

### 설계 판단
- **`active:` ≠ `avail:`**: 활성 세션 수(입장 슬롯)와 가용 좌석 풀은 별개. 입장은 "예매 진행 중 인원" 제한, 선점은 "좌석" 제한.
- **`leave()` 는 호출처가 아직 없음**: 세션 종료(예매 확정 T3-8 / 취소 / 만료 T3-9)에서 활성 슬롯을 회수할 진입점으로 미리 둠. 현재 입장만으로는 슬롯이 TTL로만 회수됨 — T3-8/9에서 연결.
- **토큰 검증(resolve)도 호출처 미연결**: T3-6 예매 API가 토큰을 받아 `resolve` → `EntrySession` 으로 검증할 예정. T3-3~5는 발급까지.

### 테스트 (`AdmissionServiceTest`, Mockito 4건)
핵심 = INCR-rollback 상한 로직:
- K 미만 → 토큰 발급 + DECR 롤백 안 함
- 정확히 K → 아직 허용(초과 아님, 경계)
- K 초과 → DECR 롤백 + Rejected, 토큰 미발급
- `leave` → 카운터 감소

> `EntryTokenStore` 의 Redis 조작·`resolve` 파싱과 실제 동시 호출 원자성은 통합 테스트(T3-11) 책임.

### 검증 결과
- `compileJava` ✅
- `AdmissionServiceTest` 4건 ✅
- 전체 테스트 ✅ — 설정 바인딩 컨텍스트 로딩 정상, 회귀 없음(46s)

---

## T3-6 / T3-7 — 예매 API (`POST /api/reservations`) (2026-06-08)

### 결정
단일 엔드포인트가 `mode` 로 SEAT/AUTO 를 분기. 컨트롤러는 **토큰 게이트 + result→HTTP 매핑**만 담당하는 얇은 계층, 예매 로직은 `BookingService` 위임.

| 결정 | 채택 | 근거 |
|------|------|------|
| 엔드포인트 | SEAT/AUTO **단일** `POST /api/reservations`, body `mode` 분기 | 동일 자원(예매) 생성, 선점 인벤토리 공유. mode 별 분리는 중복 |
| 신뢰 경계 | userId/scheduleId 는 **토큰(`EntrySession`)에서만** | body 의 위변조 식별자로 남의 세션 예매하는 길 차단. body 는 mode + (SEAT 시) seatInventoryId 만 |
| 토큰 검증 | 컨트롤러가 `EntryTokenStore.resolve` 게이트 | 누락/만료/무효 → 401 일괄. T3-5 발급과 짝 |
| result 매핑 | `BookingResult` exhaustive `switch` | 201/409/410/503 누락을 컴파일러가 검출(T3-5b 채택 효과) |

### API
```
POST /api/reservations   header: X-Entry-Token: {token}   body: { mode, seatInventoryId? }
  201 + { reservationId, expiresAt }    (성공, HELD)
  401                                   (토큰 누락/만료/무효)
  400                                   (SEAT 인데 seatInventoryId 누락)
  409                                   (SEAT 선점 패배 — SeatTaken)
  410                                   (AUTO 잔여석 없음 — SoldOut)
  503 + Retry-After                     (Overloaded — 입장 제어서 선차단, 방어 분기)
```

### 변경 범위
| 파일 | 변경 |
|------|------|
| `booking/BookingController.java` | **신규** — 토큰 게이트 + mode 분기 + `BookingResult`→HTTP. `ReservationRequest`/`ReservationResponse` record |
| `booking/BookingMode.java` | **신규** — SEAT/AUTO enum |
| `admission/EntryTokenStore.java` | `resolve` 가시성 공개(컨트롤러 게이트가 사용) |

### 설계 판단
- **컨트롤러는 얇게**: 선점·DB 전이는 `BookingService`(T3-5b 완료)에 있고, 컨트롤러는 인증 게이트와 표현 매핑만. 테스트 대상도 이 두 가지로 한정.
- **`Overloaded` 방어 분기**: 입장 제어(T3-3~5)에서 이미 걸러져 예매 단계엔 도달하지 않지만, exhaustive `switch` 를 위해 503 + Retry-After 로 매핑만 둠.
- **Spring Boot 4.0 web 슬라이스 복원**: `@WebMvcTest`/`MockMvc` 가 4.0에서 별도 모듈로 분리·재배치됨. `spring-boot-starter-webmvc-test` 추가로 합의했던 슬라이스 테스트 전략을 그대로 유지(순수 단위 폴백 불필요). import 경로 2건 이동(`WebMvcTest`, Jackson 3 `tools.jackson.databind.ObjectMapper`).

### 테스트 (`BookingControllerTest`, `@WebMvcTest` 슬라이스 6건)
컨트롤러 고유 로직 = 토큰 게이트 + result 매핑 + 신뢰 경계:
- 토큰 헤더 없음 → 401, 예매 **시도 안 함**(`never()`)
- 무효 토큰 → 401, 예매 시도 안 함
- SEAT 인데 좌석 미지정 → 400, 예매 시도 안 함
- SEAT 성공 → 201 + `reservationId`, **토큰의 userId/scheduleId 로 호출**(신뢰 경계 verify)
- SeatTaken → 409 / SoldOut → 410

> 중첩 스터빙 함정 발견: `thenReturn(Success(reservation(...)))` 처럼 인자 안에서 mock 을 stub 하면 바깥 stub 미완료로 `UnfinishedStubbingException`. mock 을 `when()` 바깥에서 먼저 생성해 해소.
> HTTP 바인딩(JSON 역직렬화·헤더 파싱)은 프레임워크 동작이라 슬라이스로 한 번 훑고, 실제 정합성은 통합 테스트(T3-11) 책임.

### 검증 결과
- `compileTestJava` ✅ (webmvc-test 스타터 해석 + import 경로 수정)
- `BookingControllerTest` 6건 ✅
- 전체 단위 테스트 ✅ — `ConcurrencyPocTest` 만 Docker 데몬 미기동(Testcontainers) 환경 사유로 제외
