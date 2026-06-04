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
| P3 기능 구현 | 13 | 0 | 0% | M3 |
| P4 성능 측정 | 12 | 0 | 0% | M4 |
| P5 비동기 | 3 | 0 | 0% | — |
| P6 산출물 | 9 | 0 | 0% | M5 |
| **합계** | **52** | **15** | **29%** | |

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
- [ ] **T3-3** 입장 제어: 활성자 카운터(상한 K)
- [ ] **T3-4** 초과 시 429/503 + Retry-After
- [ ] **T3-5** EntryToken 발급/만료/검증
### ② 예매/결제
- [x] **T3-5b** (설계 재검토) 예매 결과 반환 타입 결정 → **sealed `BookingResult` 채택** (record: Success/SeatTaken/SoldOut/Overloaded). 근거: 경쟁 패배가 1,000 요청 중 다수 = 정상 흐름 → 예외 부적합(값으로 표현), 컨트롤러가 exhaustive `switch` 로 사유별 HTTP(201/409/410/503+Retry-After) 매핑 시 누락을 컴파일러가 검출. `BookingService`/`LockBookingService` 공개 진입점의 `@Nullable Reservation` 반환 제거. 트랜잭션 내부 헬퍼(`BookingTransactionHelper`)는 `@Nullable` 유지, 매핑은 `LockBookingService` 가 담당. `Overloaded` 는 타입만 정의(발생은 입장 제어 T3-3~5). `ConcurrencyPocTest` 성공 판정은 `instanceof Success` 로 전환.
- [ ] **T3-6** 예매 API `mode=SEAT` (P2 선점 통합)
- [ ] **T3-7** 예매 API `mode=AUTO`
- [ ] **T3-8** 결제 확정(HELD→SOLD) + 취소 + 카운터/활성자 동기화
- [ ] **T3-9** HELD TTL 만료 스케줄러 → 좌석/카운터/`avail` 복구
- [ ] **T3-9b** (follow-up) 시간 소스 단일화: `Clock` 빈을 `BookingTransactionHelper`(락 경로, `now()` 동일 패턴)·`Reservation`·`SeatInventory`에도 주입해 흩어진 `LocalDateTime.now()`를 `now(clock)`로 통일. 만료 스케줄러(T3-9)가 시간 의존이므로 그 시점에 함께 정리해 만료 판정 로직을 결정적으로 테스트 가능하게 만든다. (BookingService는 선반영됨)
- [ ] **T3-10** Redis-DB reconcile 잡: 잔여/활성자 카운터·`avail` 드리프트 주기 보정 → DB(SoT)로 수렴
- [ ] **T3-11** 통합 테스트(정합성 자동화): 중복/초과/만료복구 + reconcile 수렴 검증
- **DoD(M3)**: 정상 16단계 E2E + 예외 6종 처리

## P4. 성능 측정 → 🏁 M4
- [ ] **T4-1** 부하 환경 구축(k6/nGrinder)
- [ ] **T4-2** 서버 모니터링(CPU/메모리/DB커넥션/Redis 지연)
- [ ] **T4-3** L1 직접선택 단일좌석 경쟁(정합성)
- [ ] **T4-4** L2/L2b 정상·자동배정 처리량
- [ ] **T4-5** L3 조회 폭주
- [ ] **T4-6** L4 입장 초과
- [ ] **T4-7** L5 임계점 탐색 → **활성자 상한 K 역산·확정**
- [ ] **T4-8** L6 지속 부하(soak)
- [ ] **T4-9** 실험 E1(선점/락)·E2(입장 제어)·E3(조회 캐시) Before/After + 그래프
- [ ] **T4-10** 실험 E5: 가상 스레드(Virtual Thread) on/off 성능 비교 — `spring.threads.virtual.enabled` 토글, 동일 부하(L2)에서 처리량·p95/p99·스레드 점유 Before/After + 그래프. 락 대기(Redisson)·DB I/O 블로킹 구간이 캐리어 스레드를 점유하지 않음을 검증. (JDK 21+ / Spring Boot 4.0, JDK 24 JEP 491로 synchronized 핀닝 해소)
- [ ] **T4-11** 실험 E6: 분산 락 라이브러리 비교 — **Redisson** vs 대안(① Spring Integration `RedisLockRegistry`, ② 직접 구현 Lettuce `SET NX PX` + Lua 해제, ③ (선택) ZooKeeper Curator `InterProcessMutex`). 동일 부하(L1 단일좌석 경쟁)에서 **초과 판매 0건 정합성 유지를 전제**로 처리량·p95/p99·락 획득 지연·CPU/네트워크 RTT를 Before/After + 그래프로 비교. Redisson 부가기능(watchdog 자동 갱신, pub/sub 기반 대기 vs 스핀 폴링, 재진입, fair lock)이 성능·구현 복잡도·운영 안정성에 미치는 영향을 분석하고, 락 라이브러리 선택 트레이드오프 근거를 README에 기록. (T2-4 Redisson PoC 재사용, `test/e6-lock-lib-comparison` 브랜치)
  - 구현: 각 라이브러리를 `infra.DistributedLock` 인터페이스 구현체로 추가 → `@Qualifier`/프로파일로 토글, 호출 측(`LockBookingService`) 무변경. (Redisson 결합은 이미 `RedissonDistributedLock`으로 분리됨)
  - 테스트: 두 번째 구현체 투입 시 `DistributedLock` **계약 테스트를 추상 베이스 테스트로 추출**(미획득 시 null·action의 null 통과 등 구현체 공통 행위). 인터럽트 복원·`unlock` 가드 등 라이브러리 고유 디테일은 각 구현체 테스트에 둔다.
- [ ] **T4-12** 실험 E7: 선점 백엔드(in-memory 스토어) 비교 — **Redis Set** vs **Memcached**. `MemcachedPreemption` 을 `SeatPreemption` 인터페이스 구현체로 추가(spymemcached 등 클라이언트 + docker-compose memcached). Memcached는 Set·원자 SREM/SPOP가 없어 **SEAT 선점은 좌석별 키 `add`(존재 시 실패=원자 점유), AUTO는 Set 부재로 별도 인덱스/CAS 우회 필요** — 이 *부적합성 분석 자체가 기술선택 트레이드오프 근거*(C6). 동일 부하(L1)에서 **초과 판매 0건 전제**로 처리량·p95/p99·라운드트립을 Before/After + 그래프로 비교, README 기록.
  - 구현: `@ConditionalOnProperty(name="booking.preemption", havingValue=…)` 로 구현체 토글, 호출 측(`BookingService`) 무변경. (선점 추상화는 이미 `SeatPreemption`/`RedisSetPreemption` 으로 분리됨)
  - Valkey/KeyDB/Dragonfly 등 **Redis 와이어 호환** 스토어는 구현체 불필요 — `RedisSetPreemption` 그대로 두고 접속 엔드포인트만 교체해 부하·비용 벤치마크(코드 변경 0).
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
- [ ] **T6-8** 프로젝트에 쓰인 Redis 핵심 기능 정리 — 선점 게이트(Set `SREM`/`SPOP`)·잔여/활성자 카운터·`EntryToken` TTL·분산 락(Redisson) 등 실제 사용한 Redis 자료구조·명령·패턴을 용도·일관성 등급(강/약)·DB(SoT) reconcile 관계와 함께 정리 → README/문서 반영
- [ ] **T6-9** 기존 KTX(코레일) 앱 예약 방식 대비 개선점 정리 — 실제 코레일 예약 흐름(가시적 대기열, 좌석 선점 후 결제 단계 등)과 본 프로젝트 설계(보이지 않는 입장 제어·Redis Set 원자 선점·2-tier 일관성·HELD TTL 자동 복구)를 항목별로 대조해 개선점·트레이드오프를 정리 → README 반영. C7(나만의 관점) 근거로 활용
- **DoD(M5)**: README + 배포/영상 + 체크리스트 완료

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
