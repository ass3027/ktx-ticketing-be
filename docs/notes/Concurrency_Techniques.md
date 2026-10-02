> 상위 문서: `KTX_Ticketing_Design.md`
> 출처: 2026-10-02 세션 — `admission`/`booking`/`schedule`/`domain`/`infra` 코드 전수 확인으로 정리
> 관련: `Interview_QA.md`(Q1·Q4~Q11), `../KTX_Ticketing_Reconcile_Design.md`

# 락 · 동시성 처리 기법 정리

## 0. 한 줄 요약

경합은 **Redis 원자 선점 한 점**(`avail:{scheduleId}` 의 `SREM`/`SPOP`)에서 가리고, 정확성은 **DB**(`SeatInventory.@Version` + `uk_active_seat` + 도메인 상태 가드)가 최종 보장한다. Redis 와 DB 는 원자적으로 묶이지 않으므로 **커밋 후 부수효과**로 순서를 지키고, 남는 드리프트는 **reconcile** 이 DB 기준으로 수렴시킨다. 분산 락(Redisson)은 운영 예매 경로에 쓰지 않는다.

---

## 1. 기법 목록

### 1-1. Redis 앞단 — 빠른 게이트

| 기법 | 위치 | 막는 것 | 한계 |
|---|---|---|---|
| **원자 선점** — Lua 로 `SREM`(SEAT)·`SPOP`(AUTO) + 선점 시각 `HSET` 을 한 번에 실행 | `booking/RedisSetPreemption` | 같은 좌석을 두 요청이 동시에 가져가는 것. SEAT·AUTO 가 같은 Set 을 공유 | Redis↔DB 는 비원자. reconcile 이 보완 |
| **원자 카운터** — `INCR` 반환값으로 판정, 초과 시 `DECR` 롤백 | `admission/AdmissionService` | "검사 후 증가" 사이 race 로 K 를 넘는 입장 | 경계 근처 거짓 거절 가능. 슬롯 누수(B-3) |
| **분산 락** — Redisson `tryLock(wait 5s, lease 10s)` | `infra/RedissonDistributedLock` | ① `LockBookingService` — E1 비교용 PoC(운영 경로 아님) ② 조회 캐시 single-flight | §3-3 |
| **single-flight** — 락 획득 후 캐시 재확인(double-checked) | `schedule/ScheduleListCache` | 핫키 만료 순간 요청이 몰려 DB 로 쏟아지는 것(stampede) | 락 대기 타임아웃 시 락 없이 직접 계산으로 폴백 |

### 1-2. DB — 최종 방어선

| 기법 | 위치 | 막는 것 |
|---|---|---|
| **낙관락 `@Version`** — `SeatInventory` 에만 존재 | `domain/SeatInventory` | 같은 버전을 읽은 **동시 쓰기**. 예약의 확정·취소·만료는 항상 좌석도 함께 바꾸므로 좌석 버전 하나가 예약 전이까지 보호 |
| **부분 유니크 `uk_active_seat`** — 생성 컬럼(`active_seat_inventory_id`) | `db/migration/V2__active_seat_unique.sql` | 한 좌석에 활성(HELD/CONFIRMED) 예약 2건. **순차적 stale 선점을 막는 유일한 장치**(§2) |
| **유니크 `(schedule_id, seat_id)`** | `domain/SeatInventory` | 같은 운행편·좌석 재고 중복 생성 |
| **도메인 상태 가드** — 잘못된 상태 전이 시 예외 | `domain/Reservation`, `SeatInventory.confirm/release` | 이중 확정, 만료 예약 취소, 이중 반환(`SADD` 중복 → oversell) |
| **상태 재확인 멱등** | `booking/ReservationLifecycleTransactionHelper` | 이중 취소 → no-op. 만료 sweep 과 사용자 확정·취소가 겹치면 sweep 이 no-op |
| **예외 → 409 매핑** | `booking/BookingExceptionHandler` | `OptimisticLockingFailure`·`DataIntegrityViolation` 이 500 으로 새는 것 |

### 1-3. 순서 · 경계

| 기법 | 위치 | 막는 것 |
|---|---|---|
| **커밋 후 부수효과** — `SADD`/`DECR` 은 실제 전이가 일어난 경우에만, 커밋 이후 실행 | `booking/ReservationLifecycleService`, `booking/HeldExpiryService` | 롤백됐는데 Redis 만 먼저 풀려 생기는 oversell·과다 입장 |
| **락 → 트랜잭션 → 커밋 → 언락** — 트랜잭션을 별도 빈으로 분리 | `booking/LockBookingService` → `booking/BookingTransactionHelper` | 커밋 전에 락이 풀려 다음 요청이 미커밋 상태를 읽는 것 |
| **건별 독립 트랜잭션** | 만료 sweep(`HeldExpiryService`) | 한 건 실패로 배치 전체가 롤백되는 것 |

### 1-4. 사후 수렴 · 스케줄러

| 기법 | 위치 | 막는 것 |
|---|---|---|
| **방향 비대칭 reconcile** — stale 은 항상 `SREM`, missing 은 선점 시각이 grace(5분)를 지난 것만 `SADD` | `booking/reconcile/ReconciliationService` | 커밋 직전(in-flight) 선점 좌석을 되살려 생기는 oversell |
| **`@Scheduled(fixedDelay)`** | `HeldExpiryScheduler`, `ReconciliationScheduler` | 같은 인스턴스 안 작업 겹침. 다중 인스턴스 동시 실행은 상태 재확인·`@Version`·멱등 Set 연산으로 안전(중복 작업만 낭비) |

---

## 2. 경쟁 상황별 방어 장치

| 경쟁 상황 | 막는 장치 |
|---|---|
| 같은 좌석에 SEAT 1,000건 | `SREM`(1차). 선점 off(E1 Before)면 `@Version` |
| 같은 좌석을 SEAT·AUTO 가 동시에 노림 | 같은 Set 하나 |
| **Redis 에 stale 좌석이 남았거나 failover 로 `SREM` 이 유실돼 이미 팔린 좌석을 선점** | **`uk_active_seat` 만.** 최신 버전을 읽으므로 `@Version` 통과, `markHeld()` 는 상태 미검사 |
| 확정·취소·만료가 같은 예약에 동시 발생 | 상태 재확인 + 좌석 `@Version` → 1건만 성공 |
| 입장 상한 경쟁 | `INCR` 반환값 |
| reconcile 과 진행 중 선점이 겹침 | 선점 시각 grace |
| 캐시 만료 순간 요청 쇄도 | single-flight |
| 트랜잭션 롤백과 Redis 반환 순서 | 커밋 후 부수효과 |

> `@Version` 과 `uk_active_seat` 는 중복 방어가 아니다. `@Version` 은 **동시성**(같은 순간 같은 버전), `uk_active_seat` 는 **순차적 불일치**(Redis 가 틀린 경우)를 막는다.

---

## 3. 점검 결과 (미조치)

### 3-1. `SeatInventory.markHeld()` 에 상태 가드가 없다
`confirm()`·`release()` 는 상태를 검사하지만 `markHeld()` 는 SOLD 좌석도 HELD 로 덮어쓴다. stale 선점은 유니크 제약(INSERT 실패 → 롤백)으로만 막힌다.
- **제안**: `if (status != AVAILABLE) throw` 추가 → 도메인 계층에서도 차단(방어선 +1). 회귀 테스트 동반.

### 3-2. `SREM` 이 `@Transactional` 안에서 실행된다
`BookingService.bookSeat`/`bookAuto` 가 트랜잭션 메서드이고 `LazyConnectionDataSourceProxy` 설정이 없어, 트랜잭션 시작 시 커넥션을 잡는다. 즉 **선점 패배자도 Redis 왕복 동안 DB 커넥션을 빌린다**. README 의 "패배자는 DB 미접촉"은 쿼리 기준으로 맞고 커넥션 기준으로는 아니다.
- **제안**: 조회 경로 ②(T4-5, `SCARD` 를 tx 밖으로)와 같은 방식으로 선점을 트랜잭션 밖으로 이동. 효과 크기는 Hikari active/usage 로 Before/After 측정 후 판단.

### 3-3. Redisson lease 10초 고정 → watchdog 비활성
lease 를 지정하면 Redisson watchdog 자동 갱신이 꺼져, 임계영역이 10초를 넘으면 실행 중 락이 풀린다. 사용처가 E1 PoC·캐시뿐이라 현재 영향은 작다(캐시는 두 번 계산될 뿐 결과 정확성 무관).

### 3-4. `LockBookingService` 가 락 획득 실패를 `SeatTaken`/`SoldOut` 으로 뭉갠다
실패 원인을 구분하지 못한다. 비교용 PoC 라 우선순위 낮음.

### 3-5. `Reservation` 에 `@Version` 이 없다
좌석 버전이 예약 전이까지 보호하는 의도된 설계로 보이나, 좌석을 건드리지 않는 예약 전이(예: 메모 수정)가 생기면 그 전이는 보호받지 못한다.
