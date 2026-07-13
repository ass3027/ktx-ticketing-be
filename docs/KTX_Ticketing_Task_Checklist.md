# KTX 예매 시스템 — 작업 체크리스트 (Task Tracker)

> 출처: `KTX_Ticketing_Development_Plan.md`
> 사용법: 작업 시작 시 상태를 `[ ]→[~]→[x]`로 갱신. 막히면 `(!)` 표시 + 메모.
> 상태 범례: `[ ]` 대기 · `[~]` 진행중 · `[x]` 완료 · `(!)` 블로킹

---

## 진행 현황 요약 (수기 갱신)
| 페이즈 | 태스크 수 | 완료 | 진행률 | 마일스톤 |
|--------|-----------|------|--------|----------|
| P0 셋업 | 4 | 4 | 100% | — |
| P1 설계 확정 | 6 | 6 | 100% | M1 ✅ |
| P2 핵심 PoC | 5 | 5 | 100% | M2 ✅ |
| P3 기능 구현 | 13 | 13 | 100% | M3 ✅ |
| P4 성능 측정 | 13 | 7 | 54% | M4 |
| P5 비동기 | 3 | 0 | 0% | — |
| P6 산출물 | 7 | 0 | 0% | M5 |
| P7 심화 산출물 (110%) | 3 | 0 | 0% | — |
| **합계** | **54** | **35** | **65%** | |

---

## P0. 프로젝트 셋업
- [x] **T0-1** Git 레포 생성 + 브랜치 전략 + 디렉터리 구조
- [x] **T0-2** 기술 스택 확정 → Spring Boot 3.3 / Java 17 / Gradle 8.8 / MySQL 8 / Redis 7 / Redisson / k6 / GitHub Actions
- [x] **T0-3** 로컬 Docker Compose (앱 + DB + Redis) 기동 → `docker-compose.yml`
- [x] **T0-4** 기본 CI(빌드+테스트) 파이프라인 → `.github/workflows/ci.yml`
- **DoD**: `docker compose up`으로 빈 앱+DB+Redis 기동

## P1. 설계 확정 → 🏁 M1
- [x] **T1-1** 미정값 결정: HELD TTL → **5분(300초)**
- [x] **T1-2** 미정값 결정: 결제 단계 → **HELD→SOLD 2단계 유지**
- [x] **T1-3** 미정값 결정: MQ 도입 범위 → **async side only (P5 Could)**
- [x] **T1-4** ERD 확정 + JPA 엔티티 작성 (6개 클래스) → `docs/P1_Design.md`
- [x] **T1-5** Redis 키 설계: `avail`·`remain`·`active`·`entry` → `docs/P1_Design.md`
- [x] **T1-6** seed DataInitializer → seat_inventory 50,000건 / user 10,000건 (1.1초)
- **DoD(M1)**: ERD/DDL/Redis키/seed 확정 = 설계 동결 ✅

## P2. 핵심 PoC (리스크 First) → 🏁 M2
- [x] **T2-1** 직접 선택 선점 PoC: `SREM avail {seat}`
- [x] **T2-2** 자동 배정 선점 PoC: `SPOP avail`
- [x] **T2-3** DB 상태전이(AVAILABLE→HELD) + 낙관 락(version)
- [x] **T2-4** (비교용) 분산 락(Redisson) 버전 PoC
- [x] **T2-5** 동시 1,000요청 테스트 → **초과 판매 0건 확인** ✅
- **DoD(M2)**: 동시성 PoC 초과 0건 (실패 시 설계 회귀)

## P3. 기능 구현 → 🏁 M3
### ① 조회/입장
- [x] **T3-1** 운행 리스트 조회 API → `GET /api/schedules` 커서 페이징(`from`+`afterId`, limit 기본 8/최대 100). `schedule` 패키지. 잔여석/매진은 T3-2.
- [x] **T3-2** 매진/잔여석 표시 (Redis 카운터/캐시, 약한 일관성) → 잔여석 = `avail:` Set 크기(SCARD) **단일 소스** 재사용(별도 `remain:` 미도입). `ScheduleResponse.remainingSeats/soldOut`. SCARD 직렬 루프(파이프라인/캐시는 E3 측정 후).
- [x] **T3-3** 입장 제어: 활성자 카운터(상한 K) → `active:{id}` INCR-rollback(검사-후-증가 race 회피). K는 `booking.admission.max-active` 외부화(T4-7서 확정).
- [x] **T3-4** 초과 시 429/503 + Retry-After → `429 TOO_MANY_REQUESTS` + `Retry-After` 헤더. `AdmissionResult.Rejected(retryAfter)`.
- [x] **T3-5** EntryToken 발급/만료/검증 → 불투명 UUID + `entry:{token}`(TTL 10m) Redis 저장. `EntryTokenStore` issue/resolve/revoke. 검증(resolve)은 T3-6 예매가 사용.
### ② 예매/결제
- [x] **T3-5b** (설계 재검토) 예매 결과 반환 타입 결정 → **sealed `BookingResult` 채택** (record: Success/SeatTaken/SoldOut/Overloaded). 근거: 경쟁 패배가 1,000 요청 중 다수 = 정상 흐름 → 예외 부적합(값으로 표현), 컨트롤러가 exhaustive `switch` 로 사유별 HTTP(201/409/410/503+Retry-After) 매핑 시 누락을 컴파일러가 검출. `BookingService`/`LockBookingService` 공개 진입점의 `@Nullable Reservation` 반환 제거. 트랜잭션 내부 헬퍼(`BookingTransactionHelper`)는 `@Nullable` 유지, 매핑은 `LockBookingService` 가 담당. `Overloaded` 는 타입만 정의(발생은 입장 제어 T3-3~5). `ConcurrencyPocTest` 성공 판정은 `instanceof Success` 로 전환.
- [x] **T3-6** 예매 API `mode=SEAT` (P2 선점 통합) → `POST /api/reservations`(`BookingController`). 토큰(`X-Entry-Token`) 게이트 → `BookingService.bookSeat`(SREM 선점→DB HELD). **신뢰 경계**: userId/scheduleId 는 토큰(`EntrySession`)에서만, body 는 mode+seatInventoryId 만.
- [x] **T3-7** 예매 API `mode=AUTO` → 같은 엔드포인트가 mode 로 분기, `BookingService.bookAuto`(SPOP 자동배정). `BookingResult`→HTTP exhaustive 매핑(Success 201/SeatTaken 409/SoldOut 410/Overloaded 503+Retry-After). `BookingControllerTest` 슬라이스 6종(토큰 401·SEAT 미지정 400·201/409/410·신뢰 경계). Spring Boot 4.0 web 슬라이스는 `spring-boot-starter-webmvc-test`로 복원.
- [x] **T3-8** 결제 확정(HELD→SOLD) + 취소 + 카운터/활성자 동기화 → `ReservationLifecycleService`(오케스트레이터)+`TransactionHelper` 분리. DB 전이는 트랜잭션 안, Redis 부수효과(좌석 SADD·활성 슬롯 DECR)는 **커밋 후·실제 전이 시에만** → 롤백 시 조기 해제(오버셀)·이중 취소/확정 흡수. `ReservationController` `POST /{id}/confirm`(200)·`DELETE /{id}`(204), 토큰 게이트+소유권 검증+성공 시 revoke. 상태머신 가드(Reservation/SeatInventory)로 불변식 차단. 분산락 없이 `@Version` 방어. (엣지: 동시 전이 `OptimisticLockException`→현재 500, 매핑은 T3-11서)
- [x] **T3-9** HELD TTL 만료 스케줄러 → 좌석/카운터/`avail` 복구 → `HeldExpiryScheduler`(`@Scheduled`, 30s)→`HeldExpiryService.sweep`. `findExpiredHeldIds`(HELD+만료, batchSize bound) 건별 독립 트랜잭션으로 `expire`(상태 재확인→HELD→EXPIRED+좌석 AVAILABLE), 커밋 후 `returnSeat`(SADD)+`leave`(DECR)을 **실제 만료 시에만**. 경합(사용자 confirm/cancel)은 상태 재확인+`@Version`으로 흡수→분산락 불필요. T3-8과 동일 정합성 패턴.
- [x] **T3-9b** 시간 소스 단일화 → `Reservation.confirm/cancel/expire`를 `now(clock)`로 통일(`hold`와 동일 패턴), `ReservationLifecycleTransactionHelper`에 `Clock` 주입. `SeatInventory`는 이미 시각 주입식(`markHeld`), `BookingTransactionHelper`도 이미 `Clock` 보유라 무변경. 효과: 만료 판정·타임스탬프 결정적 → `ReservationTest` 단언 `isNotNull`→`isEqualTo(고정시각)` 강화.
- [x] **T3-10** Redis-DB reconcile 잡: `avail` 드리프트 주기 보정 → DB(SoT)로 수렴. §10 열린 결정 확정 → **갈래 1**(좌석별 Redis 선점시각 마커, 선점과 Lua 원자화)·**active 분리**(별도 follow-up). 방향 비대칭: stale `SREM`=상시 안전, missing `SADD`=`now−preempt_ts>grace`(in-flight 아님)일 때만 → 오버셀 차단. `ReconciliationService`/`Scheduler`/`ReconcileProperties`(interval 60s·grace 5m), 대상=미출발 스케줄. 잔여석은 SCARD 파생 자동 수렴. 단위 5+통합 6(Lua 4·수렴 2). 결과: `P3_Result.md`.
- [x] **T3-11** 통합 테스트(정합성 자동화): 중복/초과/만료복구 + reconcile 수렴 검증 → `BookingIntegrationTest`(서비스 계층 통합 8건): 정상 E2E(SEAT/AUTO 입장→예매→확정) + 예외 6종(매진/경쟁패배/입장초과/취소/만료/토큰없음). 좌석·avail·active 동기화 단언. 동시성(`ConcurrencyPocTest`)·reconcile 수렴(`ReconciliationIntegrationTest`)은 기존 통합 테스트 재사용, HTTP 401/429 매핑은 컨트롤러 슬라이스가 커버 → 중복 회피. 만료는 미래 Clock sweep 직접 조립(zone=systemDefault로 KST skew 회피). 부수: `ConcurrencyPocTest` 시드 멱등 판정을 `findFirst`→고유 train_number 한정으로 격리(컨텍스트 공유 DB FK위반·oversell 오판 교정). 전체 회귀 103건 통과.
- **DoD(M3)**: 정상 16단계 E2E + 예외 6종 처리 ✅

## P4. 성능 측정 → 🏁 M4
- [x] **T4-1** 부하 환경 구축(k6/nGrinder)
- [x] **T4-2** 서버 모니터링(CPU/메모리/DB커넥션/Redis 지연)
- [x] **T4-3** L1 직접선택 단일좌석 경쟁(정합성) → 호스트 JVM 1,000 VU 3회 **oversell=0·중복=0·win=1 일관 달성**(S4). Docker Desktop NAT 포화로 인한 컨테이너 측정 refused(631)를 격리 실험으로 규명→호스트 실행(refused≈0)으로 우회. `Run-L1-Host.ps1` 신설, BASE_URL 127.0.0.1 고정, L1 `handleSummary` 메트릭 복원. 결과: `results/P4_Result.md`.
- [x] **T4-4** L2/L2b 정상·자동배정 처리량 → 컨테이너 k6 3회. **L2**(혼합부하 1,000VU 8분): 예매 p95 348~408ms(≤500 ✅)·TPS 833~863(≥200 ✅)·5xx 0.03~0.04%(<1% ✅) 합격, 단 **조회 p95 ~0.9s SLO(200ms) 미달**(→ 캐시/경합 가설, L3·E3 Before 로 활용). **L2b**(AUTO shared-iter): 1,000석 정확 매진(reserve_ok=1000·sold_out=1000)·oversell 0·중복 0(SPOP 원자 선점). 결과: `results/P4_Result.md`.
- [x] **T4-5** L3 조회 폭주 → 조회 경로 최적화(4기법 각 토글 측정) **완료**(2026-07-07). **Before**: 3,000TPS 목표에서 포화(list p95 초단위·dropped 대량), 병목=DB 커넥션 점유시간(SCARD 를 `@Transactional` 안에서 페이지 편수만큼 직렬 왕복). **① pool sweep**: 10→50 +14%만→pool 은 지렛대 아님. **② SCARD tx 밖**(`booking.query.redis-outside-tx`): **usage −63%·처리량 +62%** — 최대 단일 지렛대. 단 여전히 SLO 미달. **③ pipeline**(`booking.query.pipeline`): 효과크기 논증으로 갈음 — N≈8 에선 레버 아님. **④ 조회 캐시**(`ScheduleListCache` single-flight·`booking.query-cache.*`=E3): **단일 핫키(A4/C4) 850→2,497 TPS·p95 8s→26ms·dropped 0 = SLO 통과(포화 해소)**. 캐시 켜지면 ①②③ 잉여(A4≈C4). **다중 키(L3b, from 50개)에선 만료 스파이크로 p95 SLO 초과 재현 → jitter 부분 완화(525→265ms, SLO 여전 초과) → 캐시는 히트율 의존** 한계 규명. 설계·측정: `docs/plans/Query_Path_Optimization_Plan.md`·`docs/results/P4_Result.md`(T4-5). 연관: T3-2 SCARD·T4-9 E3.
- [x] **T4-6** L4 입장 초과 → 컨테이너 k6 본 측정 3회(K=100·RATE=500·3분+램프) 일관 그린. server_errors=0(B-2 수정 전 100)·admission_reject_rate 85.7~85.8%(초과분 429 흡수, S5 정상)·http_req_failed{entry}=0%·dropped=0·reserve p95 69~97ms(<500)·k6Exit 0. B-2 재예매 수정을 smoke→본 측정으로 확정. 결과: `results/P4_Result.md`.
- [x] **T4-7** L5 임계점 탐색 → **활성자 상한 K 역산·확정** → **K=100(스케줄당) 확정**(2026-07-10). L5(혼합)서 병목=예매 write 규명 → **`L5b_booking_breakpoint.js`(예매 경로 격리, list 제거·`session_duration` Trend) 신설**로 재측정. 계단 50→300 TPS: safe_booking_TPS≈150(150까지 완결 추종, 200서 임계점·VU 폭증, 300 포화=서버 천장 ~189). W≈0.9s(Little's law VU/TPS, 안전 plateau 일관). **K ≈ 150×0.9×마진0.75 ≈ 100** = 잠정값과 수렴(측정이 사후 검증). 단위=스케줄당 유지(§4 (가), 코드 0)—병목은 전역(DB pool)이나 전역 K는 후속. 정합성 수동 audit 0(오버셀 0, M2 green). 부수: `checkConsistency` body null 방어(거짓 통과 차단). `application.yml`·`AdmissionProperties` 근거 확정. 설계·진행 로그: `docs/plans/Admission_K_Calibration_Plan.md`, 결과: `P4_Result.md` T4-7.
- [x] **T4-8** L6 지속 부하(soak) — 부하 SLO 그린 + 정합성 게이트 green(L6_after K=2000: violation 0·k6Exit 0) + 시계열 우상향 없음(steady p95 −8.4ms/min, 하향 안정). 잔여 2건(B-1 해소 후 게이트·시계열 판정)을 T4-13 측정으로 해소 (`docs/results/P4_Result.md` T4-8 완료 확인)
- [x] **T4-9** 실험 E1(선점/락)·E2(입장 제어)·E3(조회 캐시) Before/After + 그래프 **완료**(2026-07-12). **E1(선점 SREM off/on)**: 재정의 — "선점 off→oversell" 은 불성립(`@Version`/`uk_active_seat` 가 방어, 양쪽 oversell=0). E1 이 증명하는 것은 *정확성을 DB 에만 맡길 때의 처리 비용* — 선점 on 이 999 패배를 Redis 앞단에서 즉시 409 반려해 DB 미접촉 → reserve 중앙값 ~1.2s→~0.29s(~4×)·availDrift off=1/on=0. 부산물로 `OptimisticLockException→409` advice 매핑 하드닝(T3-8/T3-11 부채 해소). **E2(입장 제어 K off/on)**: off(K=999999)=5xx 아닌 *무한 열화*(p95 22s·dropped 38k·VU 2000 팽창), on(K=100)=초과 85.4%(89.6k) 429 흡수·dropped 0·reserve p95 ~28ms·정합성 0. **E3**=[[T4-5]] ④ 캐시 off/on(850→2,497 TPS). 측정: 미출발 스케줄 20 타깃(시드 날짜 노후화 회피). 결과: `docs/results/P4_Result.md`(§T4-9).
- [ ] **T4-10** 실험 E5: 가상 스레드(Virtual Thread) on/off 성능 비교 — `spring.threads.virtual.enabled` 토글, 동일 부하(L2)에서 처리량·p95/p99·스레드 점유 Before/After + 그래프. 락 대기(Redisson)·DB I/O 블로킹 구간이 캐리어 스레드를 점유하지 않음을 검증. (JDK 21+ / Spring Boot 4.0, JDK 24 JEP 491로 synchronized 핀닝 해소)
- [ ] **T4-11** 실험 E6: 분산 락 라이브러리 비교 — **Redisson** vs 대안(① Spring Integration `RedisLockRegistry`, ② 직접 구현 Lettuce `SET NX PX` + Lua 해제, ③ (선택) ZooKeeper Curator `InterProcessMutex`). 동일 부하(L1 단일좌석 경쟁)에서 **초과 판매 0건 정합성 유지를 전제**로 처리량·p95/p99·락 획득 지연·CPU/네트워크 RTT를 Before/After + 그래프로 비교. Redisson 부가기능(watchdog 자동 갱신, pub/sub 기반 대기 vs 스핀 폴링, 재진입, fair lock)이 성능·구현 복잡도·운영 안정성에 미치는 영향을 분석하고, 락 라이브러리 선택 트레이드오프 근거를 README에 기록. (T2-4 Redisson PoC 재사용, `test/e6-lock-lib-comparison` 브랜치)
  - 구현: 각 라이브러리를 `infra.DistributedLock` 인터페이스 구현체로 추가 → `@Qualifier`/프로파일로 토글, 호출 측(`LockBookingService`) 무변경. (Redisson 결합은 이미 `RedissonDistributedLock`으로 분리됨)
  - 테스트: 두 번째 구현체 투입 시 `DistributedLock` **계약 테스트를 추상 베이스 테스트로 추출**(미획득 시 null·action의 null 통과 등 구현체 공통 행위). 인터럽트 복원·`unlock` 가드 등 라이브러리 고유 디테일은 각 구현체 테스트에 둔다.
- [ ] **T4-12** 실험 E7: 선점 백엔드(in-memory 스토어) 비교 — **Redis Set** vs **Memcached**. `MemcachedPreemption` 을 `SeatPreemption` 인터페이스 구현체로 추가(spymemcached 등 클라이언트 + docker-compose memcached). Memcached는 Set·원자 SREM/SPOP가 없어 **SEAT 선점은 좌석별 키 `add`(존재 시 실패=원자 점유), AUTO는 Set 부재로 별도 인덱스/CAS 우회 필요** — 이 *부적합성 분석 자체가 기술선택 트레이드오프 근거*(C6). 동일 부하(L1)에서 **초과 판매 0건 전제**로 처리량·p95/p99·라운드트립을 Before/After + 그래프로 비교, README 기록.
  - 구현: `@ConditionalOnProperty(name="booking.preemption", havingValue=…)` 로 구현체 토글, 호출 측(`BookingService`) 무변경. (선점 추상화는 이미 `SeatPreemption`/`RedisSetPreemption` 으로 분리됨)
  - Valkey/KeyDB/Dragonfly 등 **Redis 와이어 호환** 스토어는 구현체 불필요 — `RedisSetPreemption` 그대로 두고 접속 엔드포인트만 교체해 부하·비용 벤치마크(코드 변경 0).
- [x] **T4-13** (선행: T4-8) 만료 sweep 벌크 UPDATE 최적화 — 건별 처리(현재 1 SELECT fetch join + 2 UPDATE + 건별 트랜잭션, **O(N) roundtrip**)를 배치 처리(**O(1)**)로 전환하고 Before/After 측정. "측정 후 최적화" 원칙(T3-2 SCARD·E3와 동일).
  - **트리거 조건**: T4-8(L6 soak)에서 sweep 이 병목으로 측정될 때만 착수. 미측정 시 현 건별 설계(T3-9) 유지.
  - **구현 범위**: ① 만료 대상 `(reservationId, seatInventoryId, scheduleId)` 매핑 1 SELECT → ② `UPDATE Reservation SET status=EXPIRED, version=version+1 WHERE status='HELD' AND expiresAt < :now` → ③ `UPDATE SeatInventory SET status=AVAILABLE WHERE ...` (≈ 3 roundtrip / O(1)). 토글로 건별/벌크 전환해 비교.
  - **정합성 함정(반드시 처리)**: Redis 부수효과(`returnSeat` SADD·`leave` DECR)를 **UPDATE 전 SELECT 결과로 돌리면 안 됨** — SELECT~UPDATE 사이 사용자 confirm(HELD→SOLD)된 행은 `WHERE status='HELD'`로 UPDATE에선 빠지지만 SELECT엔 남아 SOLD 좌석을 가용 풀에 반환 = **오버셀**. 실제 EXPIRED 전이된 행만 부수효과 대상이 되도록 보장(전이 후 재조회 또는 잠금). 도메인 상태머신(`Reservation.expire()`) 우회 + `@Version` 수동 증가(사용자 confirm 경로 lost-update 방어)를 직접 재구축해야 함.
  - **수용 기준**: sweep DB roundtrip 이 만료 건수와 무관(O(1)) + 오버셀 0(T3-11 정합성 테스트에 confirm-vs-sweep 경합 케이스 추가) + Before/After roundtrip·지연 수치/그래프 기록.
- [ ] **T4-14** (T4-7 후속) 전역 활성자 상한 K 도입 — **스케줄당 K의 균등 분산 취약점 해소**. 현 `active:{scheduleId}` 는 스케줄당 상한이라 K=100×50스케줄=이론상 전역 5,000 세션 허용(서버 write 천장 ~189 대비 과다) → 부하가 여러 스케줄에 고루 퍼지면 전역 보호가 뚫린다. 실전은 소수 인기 노선 쏠림(동시 인기 ~1개)이라 K=100 이 "동시 인기 스케줄 1개" 보수 가정으로 작동하나(T4-7 §9.3), 그 가정이 깨지는 시나리오 대비.
  - **구현**: `AdmissionService` 에 `active:global` INCR-rollback 을 기존 스케줄당 게이트 **위에 AND 조건**으로 추가(둘 다 통과해야 입장). 전역 상한 = 서버 write 천장 근거(~189×W). 세션 종료(예매 확정/취소/만료)·토큰 만료 시 전역 카운터도 함께 DECR — 스케줄당과 동일한 커밋-후 부수효과 패턴 재사용.
  - **테스트**: `active:global` 검사-후-증가 race(스케줄당과 동일 INCR-rollback 검증) 추가. 전역 상한 발동 시 429 흡수를 L4 재측정(다중 스케줄 동시 인기 셋업)으로 확인.
  - **트레이드오프 기록(C6)**: 스케줄당 vs 전역 K 단위 불일치를 측정으로 규명(T4-7)한 뒤 전역 K 로 해소하는 서사를 README 에 남긴다. 카운터 1개 추가 비용 vs 균등 분산 보호의 트레이드오프.
- **DoD(M4)**: SLO 충족/미달 사유 + Before/After 그래프 + 임계점 수치

## P5. 비동기 사이드 (Could)
- [ ] **T5-1** MQ 연동: 예매확정/취소/만료 이벤트 발행
- [ ] **T5-2** 컨슈머: 알림(mock)/통계 적재 + **멱등성** 처리
- [ ] **T5-3** (선택) E4: Redis 동기 vs MQ 비동기 비교 PoC

## P6. 산출물 & 마무리 → 🏁 M5
- [ ] **T6-1** README: 문제정의·아키텍처·기술선택 이유·트레이드오프·성능 그래프
- [ ] **T6-2** 아키텍처/시퀀스 다이어그램 이미지화
- [ ] **T6-3** 배포(URL) — 차단 시 영상 보완
- [ ] **T6-4** 동작 영상: 정상 흐름
- [ ] **T6-5** 동작 영상: 동시성 시연 + 부하 결과
- [ ] **T6-6** 셀프 체크리스트 7항목 점검(`Portfolio_Project_Evaluation_Criteria.md`)
- [ ] **T6-7** 최종 점검 + 제출
- **DoD(M5)**: README + 배포/영상 + 체크리스트 완료

## P7. 심화 산출물 (110% · Stretch · M5 이후)
> M5(100% 완성) DoD 에는 속하지 않는 가산 항목. 제출 가능 상태를 확보한 뒤 여유 시 착수해
> 포트폴리오 변별력(C4·C7)을 보강한다. 미완이어도 M5 합격에는 영향 없음.
- [ ] **T7-1** 프로젝트에 쓰인 Redis 핵심 기능 정리 — 선점 게이트(Set `SREM`/`SPOP`)·잔여/활성자 카운터·`EntryToken` TTL·분산 락(Redisson) 등 실제 사용한 Redis 자료구조·명령·패턴을 용도·일관성 등급(강/약)·DB(SoT) reconcile 관계와 함께 정리 → README/문서 반영
- [ ] **T7-2** 기존 KTX(코레일) 앱 예약 방식 대비 개선점 정리 — 실제 코레일 예약 흐름(가시적 대기열, 좌석 선점 후 결제 단계 등)과 본 프로젝트 설계(보이지 않는 입장 제어·Redis Set 원자 선점·2-tier 일관성·HELD TTL 자동 복구)를 항목별로 대조해 개선점·트레이드오프를 정리 → README 반영. C7(나만의 관점) 근거로 활용
- [ ] **T7-3** 블로그 정리 — 개발 중 도출한 트레이드오프·트러블슈팅을 외부 공개용 글로 정리. 후보 소재: ① **데이터 시드 JPA vs JdbcTemplate**(성능 격차의 진짜 원인 = 영속성 컨텍스트 누적, `docs/notes/Test_Seeding_Strategy.md`) ② Redis–DB reconcile 정합성 함정(`docs/KTX_Ticketing_Reconcile_Design.md`) ③ 동시 1,000요청 oversell=0 검증(선점 SREM/SPOP + 낙관락) ④ 보이지 않는 입장 제어(INCR-rollback) ⑤ **k6 포트 고갈 트러블슈팅**(Windows Docker Desktop 포트 프록시 NAT 압박 → `tcp_tw_reuse`가 답이 아닌 이유 → same-network 직결로 근본 수정, `docs/notes/K6_Port_Exhaustion_Troubleshooting.md`). C4(AI 활용)·C7(나만의 관점) 보강. 글마다 "문제→가설→측정/근거→결론" 구조 유지
- **DoD(P7)**: 가산 항목 — 착수분만큼 README/블로그에 반영 (필수 아님)

---

## 기술 부채 / 개선 백로그 (페이즈 외 · 여유 시 처리)
> 특정 페이즈 DoD 에 속하지 않는 후속 개선. 착수 시 독립 브랜치 + 계획 승인.
- [x] **B-2** 좌석 재예매 불가 버그 수정 (A-2 활성 한정 부분 유니크) — `reservation.seat_inventory_id` 의 상태-무관 전역 유니크가 취소/만료로 되돌아온 좌석의 재예매를 막아 `Duplicate entry`→500. **해결**: 스키마 관리 ddl-auto→Flyway 전환(`V1__baseline`/`V2__active_seat_unique`), `V2` 가 생성 컬럼 `active_seat_inventory_id`(활성일 때만 좌석id, 아니면 NULL)+`uk_active_seat` 로 "좌석당 활성 1건" 불변식만 강제(오버셀 DB 방어선 유지)·취소/만료는 NULL 로 공존 허용→재예매 정상화. 회귀 테스트 3종(`BookingIntegrationTest`: 취소후·만료후 재예매 성공, 활성 2건 차단). L4 smoke 그린(server_errors 100→0). 설계: `docs/plans/Seat_Rebooking_Unique_Constraint_Fix_Plan.md`. 연관: T3-9·T3-11·T4-6.
- [ ] **B-3** 입장 슬롯 누수 — "입장(active INCR) 후 예매가 생성되지 않으면" 슬롯을 회수할 경로가 없다(`AdmissionService.leave` 는 예약 confirm/cancel/만료에서만 호출). 예매 실패(예외)·중도 이탈 시 슬롯이 EntryToken TTL 만료로도 회수되지 않으면 영구 점유 → K 조기 포화. (B-2 수정으로 *Duplicate entry 5xx* 발 누수는 해소됐으나 구조적 결함은 잔존.) 점검: EntryToken TTL 만료 시 `leave` 보상이 있는지 확인, 없으면 만료 훅 또는 예매 실패 응답 경로에서 보상. 발견: 2026-06-21 L4. 연관: 입장 제어(`AdmissionService`)·T4-6.
- [~] **B-4** 시드 스케줄 출발일 상대날짜화 — `DataInitializer` 가 `departure_time = 2026-07-01 + i일` 으로 **하드코딩**이라 시간이 지나면 앞쪽 스케줄이 이미 출발 → avail 워밍업/reconcile 대상에서 빠져 `avail:{id}` 가 비고, 예매 계열 부하테스트(L1·L4·E1·E2)가 선점 on 경로에서 `reserve_ok=0`/SoldOut 으로 **거짓 실패**(선점 off 는 avail 우회라 거짓 통과). 현재는 미출발 스케줄을 수동 타깃(`-ScheduleId 20`)해 우회 중. 근본 수정=출발일을 `now()` 기준 상대날짜로. **블라스트 반경**: `config.js` FROM_DATE·L3b 의 50일 매핑도 함께 맞춰야 함. 발견: 2026-07-12 T4-9. 연관: 세션 메모리 `seed-schedule-date-staleness`·T4-6·T4-9. **코드 수정 완료(2026-07-13, `fix/b-4-seed-relative-date`)**: 계약="오늘 KST+1일 08:00" 상대날짜 — 백엔드 `DataInitializer.seedSchedules`(`LocalDate.now(Asia/Seoul).plusDays(1).atTime(8,0)`)·k6 `config.js` FROM_DATE(UTC 컨테이너 +9h→KST 08:00)·L3b BASE_DATE 3곳 동기화. `./gradlew test` 그린(테스트 소스 무변경=시드가 local 프로파일 격리 증명). **런타임 검증 대기**: Reset-Seed→app 재기동→`redis-cli KEYS 'avail:*'`=50·L1/E1/E2 `reserve_ok>0` 확인 후 `[x]`. 계획: `~/.claude/plans/b-4-ticklish-cook.md`.
- [x] **B-1** tz skew 해소 (환경 tz KST 통일) — **해결(2026-06-24, 경로 A)**: 근본 원인은 JVM(OS기본 KST)·MySQL 세션(SYSTEM=UTC)·JDBC(`serverTimezone=Asia/Seoul`) 3곳 tz 불일치. KTX(단일 리전, 운행시각=KST 벽시계)라 **전부 KST 로 통일**해 즉시 해소 — `docker-compose.yml` app·mysql `TZ: Asia/Seoul`, `docker-entrypoint.sh` 의 dump·본 부팅 java 양쪽에 `-Duser.timezone=Asia/Seoul`, JDBC url 은 기존 `Asia/Seoul` 유지. 엔티티/스키마/쿼리 변경 0. **검증**: 재기동 후 app `user.timezone=Asia/Seoul`·MySQL `NOW()`=KST 확인, 예매 1건의 `expires_at(15:42)` vs DB `NOW()(15:37)` 같은 KST 라 `is_expired=0`·audit `expiredHeld=0`(이전 9h skew 재현 없음). 회귀 125건 통과. 후속(선택) **B-1b**: `LocalDateTime`→`Instant` 전환(절대시각이라 zone 원천 소거, OS/DST 변경에도 안전)은 장기 견고성 개선으로 남김 — 엔티티 4종+컬럼 `TIMESTAMP`+Flyway+쿼리+DTO+테스트 범위. 연관: T4-8 L6·U-2 audit.
  - (참고) 원 처방이던 `Instant` 전환 상세 — 대상: `Reservation`(heldAt/expiresAt/confirmedAt/cancelledAt)·`SeatInventory`(heldAt/expiresAt)·`User.createdAt` 등 "절대 시각" 필드. **운행 일정(`Schedule.departureTime/arrivalTime`)은 KST 벽시계 표시 의미라 `LocalDateTime` 유지**(전환 대상 아님). 동기: 현재 `expiresAt`(naive `LocalDateTime`)을 프로덕션 `Clock.systemDefaultZone()`으로 찍어 저장·비교 → 저장·비교 zone 이 같아야만 정합(단일 서버에선 동작하나 OS zone 변경/DST·UTC 고정 리팩터링 시 9h skew 로 `expiresAt<now` 오판 위험). `Instant` 는 절대 시각이라 비교에서 zone 이 소거됨 → `BookingIntegrationTest` sweep Clock 의 zone 주석(systemDefault 강제) 자체가 불필요해짐. 범위: 엔티티 4종 + DB 컬럼 타입(`DATETIME`→`TIMESTAMP`/마이그레이션) + 쿼리(`findExpiredHeldIds` 등) + DTO(`expiresAt` 노출 형식) + 기존 테스트. `Clock.instant()` 를 시각 출처로 사용(현재 `LocalDateTime.now(clock)` 에서 한 단계 축약). **실측 확인(2026-06-24 L6/U-2 audit)**: 컨테이너(app=JVM 기본 KST / MySQL 세션 SYSTEM=UTC) 환경에서 `expiresAt` 이 KST 벽시계(예: 13:42)로 naive 저장돼 MySQL `NOW()`(UTC 05:05) 기준으론 미만료지만 앱 `Clock`(KST) 기준으론 만료로 보이는 9h skew 가 재현됨 → `/internal/consistency` 의 `expiredHeld≈3,700` 으로 검출(availDrift·statusViolation=0, 오버셀/드리프트 없음). 즉 가설이 아니라 환경 의존 실결함으로 확정. 연관: T4-8 L6 결과(`docs/results/P4_Result.md`)·U-2 audit.

---

## 제출 전 셀프 체크리스트 (평가기준 7항목)
- [ ] **C1** 왜 이 주제인지 한 문장 설명 가능
- [ ] **C2** 직접 설정한 품질 기준·측정 수치 보유
- [ ] **C3** 배포 URL + GitHub + README + 동작 영상 구비
- [ ] **C4** AI 활용 방식·검증 지점 설명 가능
- [ ] **C5** 핵심 코드·설계 구두 설명 가능
- [ ] **C6** 기술 선택 트레이드오프 근거 보유
- [ ] **C7** 다른 포트폴리오와 구분되는 나만의 관점

---

## 블로킹/메모 (작업 중 기록)
| 날짜 | 태스크 | 이슈 | 상태 |
|------|--------|------|------|
| | | | |
