# T3-11 — 정합성 자동화 통합 테스트 실행 계획

> 상위 문서: KTX_Ticketing_Workflow.md (16단계 happy + 예외 흐름 §3.1~3.6)
> 출처: KTX_Ticketing_Task_Checklist.md (T3-11), P3_Result.md, KTX_Ticketing_Reconcile_Design.md
> 상태: **계획 승인 완료(2026-06-09)** — 구현 착수 가능

## Context

T3-11은 P3의 마지막 task이자 **M3 DoD = "정상 16단계 E2E + 예외 6종 처리"** 의 자동화 검증이다.
지금까지 각 조각은 단위/슬라이스/PoC로 검증됐지만, **실제 HTTP→Redis→DB 전 구간을 한 번에 통과하는
end-to-end 정합성 테스트가 없다.** T3-11은 그 갭을 메운다.

### 확정된 결정 (사용자 승인)
1. **구동 계층 = 하이브리드.** 정상 16단계·HTTP 예외(매진·입장초과·토큰·취소)는 **MockMvc**(실제 컨트롤러/상태코드/토큰 게이트), 만료복구·reconcile·동시성 정합성은 **서비스+리포지토리 단언**.
2. **스케줄러 결정성 = 프로퍼티 토글.** 두 스케줄러에 `@ConditionalOnProperty(booking.scheduler.enabled, matchIfMissing=true)` → 테스트에서 `false`. 테스트는 `sweep()`/`reconcile()`을 **수동 트리거**.
3. **중복 = E2E 1본 + 갭만.** 정상 16단계 E2E는 신규, 이미 커버된 reconcile 수렴·1000동시 oversell은 **기존 테스트 인용**(재작성 안 함).

### 발견한 선결 문제
- `src/test/resources/application.yml`이 메인 yml을 **shadow** → `booking.expiry.*`·`booking.reconcile.*` 키 부재. `ReconcileProperties`/`ExpiryProperties` 바인딩과 `@Scheduled` 플레이스홀더가 테스트에서 깨질 수 있음 → 테스트 설정에 키 추가 필요(토글로 스케줄러는 끄되 바인딩용 값은 둠).
- `Clock` 빈이 `systemDefaultZone()` 고정 → 만료(book→5분 경과→sweep) E2E 불가. **제어 가능한 테스트 Clock** 필요.

---

## 선결 인프라 변경

### A. 스케줄러 토글 (프로덕션 — 소폭)
- `booking/HeldExpiryScheduler.java`, `booking/ReconciliationScheduler.java`:
  클래스에 `@ConditionalOnProperty(name = "booking.scheduler.enabled", havingValue = "true", matchIfMissing = true)` 추가.
  → 기본 on(운영 무영향), 테스트에서만 off. 운영에서도 인스턴스별 스케줄러 on/off 가능(부가가치).

### B. 테스트 설정 (`src/test/resources/application.yml`)
기존 `booking.admission.*` 유지하고 추가:
```yaml
booking:
  scheduler:
    enabled: false        # 배경 sweep/reconcile 정지 → E2E 결정적, 테스트가 수동 트리거
  expiry:
    batch-size: 100
    sweep-interval: 1h    # 토글 off라 미발화. 플레이스홀더/바인딩 안전용
  reconcile:
    interval: 1h
    preempt-grace: 5m     # 수동 reconcile 호출이 grace 비교에 사용
```

### C. 제어 가능한 테스트 Clock (test 전용)
- `support/MutableClock.java`(test): `Clock` 확장, `advance(Duration)`/`setInstant` 제공.
- `support/TestClockConfig.java`(test `@TestConfiguration`): `@Bean @Primary MutableClock clock()` — 앱의 `Clock` 빈을 오버라이드. 고정 시작 instant(예: `2026-06-09T00:00:00Z`).

---

## 신규 테스트 (공유 베이스로 컨텍스트 1개 캐시)

공유 베이스 `AbstractBookingFlowIntegrationTest extends AbstractIntegrationTest`:
`@AutoConfigureMockMvc`, `@Import(TestClockConfig.class)`, `@TestPropertySource(properties = "booking.admission.max-active=1")`(입장초과 K=1 검증 겸용, 단일 사용자 happy도 통과). 하위 두 클래스가 상속 → 동일 설정 → 컨텍스트 캐시 공유.

**작업 스케줄은 시드 `scheduleId=2`** 사용(ConcurrencyPocTest의 1과 충돌 회피). `@BeforeEach`:
좌석 3개(`findAvailableIdsByScheduleId(2)` 앞 3개) DB AVAILABLE 리셋 → `initInventory(2, seatIds)` → `active:2`·토큰 키 삭제 → 해당 좌석 예약 삭제 → `MutableClock` 시작값 리셋.

### 1) `BookingJourneyE2ETest` (MockMvc) — happy + HTTP 예외
| 테스트 | 흐름 | 단언 |
|--------|------|------|
| `정상_16단계_SEAT_E2E` | `GET /api/schedules`(조회) → `POST /api/entry`(201+token) → `POST /api/reservations`{SEAT}+token(201 HELD) → `POST /{id}/confirm`(200 CONFIRMED) | 각 단계 HTTP/바디 + DB(seat AVAILABLE→HELD→SOLD, reservation HELD→CONFIRMED) + Redis(`active:2` 1→0, avail에서 좌석 제거) + 확정 후 토큰 revoke(2차 confirm→401) |
| `정상_AUTO_E2E` | entry → reservations{AUTO}(201) → confirm(200) | SPOP 배정 좌석 HELD→SOLD |
| `매진_AUTO_410` | avail 비움 → reservations{AUTO} | 410, 예약 0 |
| `매진_SEAT_409` | 요청 좌석이 avail에 없음 → reservations{SEAT} | 409 |
| `토큰_누락_401`·`무효_토큰_401` | 헤더 없이 / 가짜 토큰 | 401, 예약 0·상태 불변 |
| `취소_204_좌석복구` | entry → book(HELD) → `DELETE /{id}`+token | 204, seat AVAILABLE, avail 재추가, `active:2`=0, 토큰 revoke |
| `입장초과_429` | userA entry(201) → userB entry(같은 schedule) | 429 + `Retry-After` 헤더, INCR-rollback로 `active:2` 정확 유지 → A leave 후 B 재시도 201 |

### 2) `BookingExpiryRecoveryIntegrationTest` (서비스) — 만료복구 갭
| 테스트 | 흐름 | 단언 |
|--------|------|------|
| `HELD_만료_sweep_복구` | `bookingService.bookSeat`(HELD, expiresAt=T0+5m) → `clock.advance(6m)` → `heldExpiryService.sweep()` | reservation EXPIRED, seat AVAILABLE, **avail 재추가(SADD)**, `active` DECR. 실제 DB+Redis 정합성(단위 `HeldExpiryServiceTest`는 모킹이라 이 통합 경로가 갭) |
| `이미_확정된건_sweep_무동작` | book→confirm(SOLD) → advance → sweep | EXPIRED 전이 안 함, 부수효과 0(상태 재확인 멱등) |

> CLAUDE.md 테스트 규칙: 단위가 이미 검증한 분기 로직은 재현 안 함. 위 통합 테스트는 "실제 트랜잭션 커밋 + Redis 부수효과 + 상태 정합"이라는 **단위가 못 잡는 결합**을 검증 → 유효.

---

## 예외 6종 / 정상 흐름 커버리지 매핑

| DoD 항목 | 커버 | 위치 |
|----------|------|------|
| 정상 16단계 (조회→입장→예매→확정) | **신규** | `BookingJourneyE2ETest.정상_16단계_SEAT_E2E` (+AUTO) |
| 예외1 매진(409/410) | **신규** | `BookingJourneyE2ETest` 매진_* |
| 예외2 입장초과(429+Retry-After) | **신규** | `BookingJourneyE2ETest.입장초과_429` |
| 예외3 HELD 만료복구 | **신규** | `BookingExpiryRecoveryIntegrationTest` |
| 예외4 취소(204+복구) | **신규** | `BookingJourneyE2ETest.취소_204_좌석복구` |
| 예외5 토큰누락(401) | **신규** | `BookingJourneyE2ETest` 토큰_* |
| 예외6 reconcile 드리프트 수렴 | **기존 인용** | `ReconciliationIntegrationTest`(T3-10) |
| 동시성 oversell=0/중복=0 | **기존 인용** | `ConcurrencyPocTest`(T2-5) |
| 단계15 MQ 이벤트 | 범위 밖 | P5 |

---

## 변경/생성 파일

**프로덕션(소폭)**: `booking/HeldExpiryScheduler.java`, `booking/ReconciliationScheduler.java` (`@ConditionalOnProperty` 1줄).
**테스트 설정**: `src/test/resources/application.yml` (scheduler/expiry/reconcile 키).
**테스트 지원(신규)**: `support/MutableClock.java`, `support/TestClockConfig.java`, `support/AbstractBookingFlowIntegrationTest.java`.
**테스트(신규)**: `booking/BookingJourneyE2ETest.java`, `booking/BookingExpiryRecoveryIntegrationTest.java`.

재사용: `AbstractIntegrationTest`(Testcontainers), `ConcurrencyPocTest.runConcurrently`/`resetState` 패턴, `SeatInventoryRepository.countByScheduleIdAndStatus`·`findAvailableIdsByScheduleId`, `ReservationRepository.countBySeatInventoryId`, `SeatPreemption.initInventory/availableSeatIds`, `AdmissionService`/`EntryTokenStore`.

---

## 검증 (end-to-end)

1. `docker compose up mysql redis` 후 `./gradlew test` — 신규 통합 테스트 그린.
2. 핵심 단언: 매 시나리오에서 **DB(SoT) 상태 + Redis(avail/active/token) + HTTP 상태**가 워크플로우와 일치.
3. **로컬 Docker 부재 시**: 컴파일 + 비-Testcontainers 단위는 로컬 확인, 통합은 **CI(GitHub Actions)** 에서 실행(기존 통합 테스트와 동일).
4. 회귀 확인: 스케줄러 토글 off가 `ConcurrencyPocTest`/`ReconciliationIntegrationTest`의 배경 간섭을 제거해 오히려 안정화.

---

## 범위 밖
- MQ 이벤트(단계15) → P5. reconcile 드리프트·1000동시 oversell → 기존 테스트 인용(재작성 안 함).
- active 카운터 reconcile → 별도 follow-up(T3-10에서 분리됨).

## 커밋 분할 (작은 단위)
1. `test`(infra): 스케줄러 `@ConditionalOnProperty` 토글 + 테스트 설정 키 + `MutableClock`/`TestClockConfig`/공유 베이스.
2. `test`: `BookingJourneyE2ETest` (정상 16단계 + 매진·입장초과·토큰·취소 HTTP 예외).
3. `test`: `BookingExpiryRecoveryIntegrationTest` (만료 sweep 복구 정합성).
4. `docs`: 체크리스트 T3-11 완료 + **M3 달성** 표기, `P3_Result.md` T3-11 섹션(시나리오 매핑·커버리지), DoD 충족 기록.
