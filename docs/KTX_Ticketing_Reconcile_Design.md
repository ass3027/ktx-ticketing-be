# KTX Ticketing — Redis–DB Reconcile 설계 노트 (T3-10)

> 상위 문서: KTX_Ticketing_Design.md
> 출처: KTX_Ticketing_Task_Checklist.md (T3-10), 설계 토론 정리 2026-06-08
> 상태: **미확정** — 아래 "열린 결정" 둘을 정해야 구현 착수 가능

T3-10(Redis–DB reconcile 잡)의 정합성 함정과 그 해법을 토론으로 도출한 기록.
다음 작업 때 같은 논의를 반복하지 않기 위함. 결론만 급하면 맨 아래 "결정 요약" 표부터.

---

## 1. 용어

- **SoT (Source of Truth)**: 데이터의 최종 정답이 있는 단일 기준 저장소. 이 프로젝트는 **DB(MySQL)가 SoT**.
  (CLAUDE.md: "DB is the source of truth; Redis counters/sets ... must reconcile back to the DB after load.")
- **드리프트(drift)**: SoT(DB)와 Redis가 서로 어긋난 상태. 둘이 원자적으로 갱신되지 않아 발생.
- **reconcile**: 드리프트를 주기적으로 **DB 기준으로** 다시 맞추는 잡(절대 그 반대 아님).

## 2. 무엇이 드리프트하는가 / 각 SoT

| Redis 상태 | DB 대응(SoT) | 비고 |
|---|---|---|
| `avail:{scheduleId}` Set (가용 좌석 풀) | `SeatInventory.status = AVAILABLE` | **DB가 SoT. T3-10의 주 대상.** |
| 잔여석 카운터 | (없음) | T3-2에서 잔여석 = `SCARD(avail)` 단일 소스 → avail 보정하면 자동 수렴. 별도 작업 없음. |
| `active:{scheduleId}` 카운터 | **DB 아님** (실질 SoT = `entry:{token}` TTL 키 집합) | "DB로 수렴" 패턴이 안 맞음. 성격이 달라 **별도 취급/분리 후보**. |

드리프트 발생 원인(avail Set):
- 선점 SREM 성공 후 DB HELD 커밋 전 크래시 → Redis엔 없고 DB는 AVAILABLE
- 예매 실패/롤백(아래 §3 코드 확인) → SREM됐는데 SADD 보상 없음 → 동일
- 취소/만료 커밋은 됐는데 `returnSeat`(SADD) 부수효과 실패 → DB AVAILABLE인데 Redis 부재

## 3. 코드 확인 결과 (현재 동작)

- `BookingService.bookSeat/bookAuto`: `tryPreemptSeat`(SREM) / `popAnySeat`(SPOP) 성공 후
  `doHold`(DB 저장)가 `@Transactional` 안에서 실행. **실패/롤백 시 좌석을 Redis로 되돌리는 보상 SADD가 없음.**
  → 예매 실패는 그대로 드리프트(AVAILABLE in DB, 부재 in Redis)를 남기며, 이는 reconcile가 고쳐야 할 케이스.
- 따라오는 성질: **Redis Set에서 빠진 좌석은 새 예매가 재선점 불가.** SREM/SPOP는 멤버에만 성공.
  좌석의 "부재" 자체가 새 선점을 막는 잠금 역할.
- `RedisSetPreemption`: 현재 `availableCount`(SCARD)·`initInventory`(delete+re-add)만 있고
  **diff에 필요한 SMEMBERS·단건 SREM 보정 메서드는 없음** → reconcile 구현 시 추가 필요.

## 4. 핵심 원리 — 방향 비대칭 (가장 중요)

reconcile는 두 종류의 불일치를 고침. **위험도가 정반대.**

| 방향 | 의미 | 잘못하면 | 심각도 |
|---|---|---|---|
| **stale 제거 (SREM)** | Redis엔 있는데 DB는 AVAILABLE 아님 | 정당 가용 좌석을 잠깐 숨김 → **언더셀** | 낮음, 자가치유 |
| **missing 추가 (SADD)** | DB는 AVAILABLE인데 Redis엔 부재 | 이미 잡힌/팔린 좌석을 되살림 → **오버셀** | **치명적** |

프로젝트 1순위 목표가 "오버셀 0"이므로 → **SADD가 위험의 전부.**
**SREM(제거)은 온라인 상시 안전. SADD(추가)에만 안전장치가 필요.**

## 5. 왜 SADD가 오버셀을 유발하나 — in-flight race

정상 예매 흐름은 `SREM(선점 win) → DB HELD 커밋`. 이 둘은 원자적이지 않다.
`[SREM ~ 커밋]` 윈도우 동안 좌석은 **"DB는 AVAILABLE, Redis엔 부재"** 상태인데,
**SREM이 DB에 흔적을 안 남기므로** 이건 진짜 드리프트와 구별 불가.
reconcile가 이 순간을 "missing 드리프트"로 오판해 SADD하면 → 같은 좌석을 두 명이 win → 오버셀.
(T4-13 sweep 벌크 최적화의 SELECT~UPDATE 함정과 동일 구조.)

## 6. 폐기된 해법들 (반례와 함께)

토론에서 제안됐다가 반례로 무너진 것들. **다시 제안하지 말 것.**

### (A) `updatedAt < now − 5분` 게이트 — 폐기
"마지막 상태 변경이 오래된 AVAILABLE만 SADD." → **틀림.**
반례: 좌석이 오래 idle(updatedAt 옛날)하다가 막 SREM됨. SREM은 DB를 안 건드리므로 updatedAt은
"가용해진 시각"이지 "선점된 시각"이 아님 → 게이트 통과 → 오버셀.
교훈: **단일 스냅샷의 DB 타임스탬프로는 in-flight를 구별 못 함(SREM이 DB에 흔적을 안 남기니까).**

### (B) 2회 관측 안정성 윈도우 (obs1/obs2, Δ > tx 타임아웃) — 불충분
"두 시점 모두 AVAILABLE+부재인 것만 SADD." 단순 시나리오(부재 좌석 재선점)는 §3 성질로 막히지만,
**`returnSeat`(취소/만료 SADD)가 끼면 뚫림.**
반례 사슬(모두 Δ 안):
`obs1 부재(A in-flight) → A commit → A 취소(SADD, X present) → B 선점(SREM, 부재) → obs2 부재(B in-flight)`
→ 두 스냅샷 다 부재라 통과 → B가 정당히 잡은 좌석 SADD → 오버셀.
교훈: **점 두 개 샘플링은 사이의 present 순간을 놓침. 필요한 건 "구간 전체에 선점 활동이 없었음"의 증명.**

## 7. 올바른 원리

> SADD(추가)는 **"그 좌석/스케줄이 관측 구간 내내 선점 활동이 없었다"가 증명될 때만** 안전하다.

이를 만족하는 두 갈래:

### 갈래 1 — 좌석별 영속 "마지막 선점 시각" 마커 (온라인 양방향)  ← 권장 후보
- 모든 SREM/SPOP가 좌석별 타임스탬프를 남김. reconcile는 그 좌석을 **1회 읽기**로 판단:
  `now − preempt_ts > 임계(예: 5분)` 인 missing 좌석만 SADD.
- §6(B)의 사슬도 막힘: **B의 선점이 타임스탬프를 새로 갱신** → reconcile가 언제 읽든 "최근 선점됨 → skip."
  영속 마커라 "사이 순간을 놓침" 문제가 없음(점 샘플링이 아니라 상태 조회).
- **저장처는 Redis로.** (DB 칼럼 안 됨 — §8 참조)
  - `HSET preempt:ts:{scheduleId} {seatId} now` 를 **선점과 한 Lua 스크립트로 원자 실행**
    (`SREM/SPOP + 타임스탬프 기록`이 한 덩어리).
  - 이점: in-flight 무방비 창 0, AUTO(SPOP) 모드 OK, DB 행 잠금 없음, INCR/HSET급 비용.

### 갈래 2 — 제거 전용(온라인) + SADD는 부하종료/정적 시점
- 온라인 reconcile는 **stale SREM만**(항상 안전). missing SADD는 트래픽 없는 시점
  (스케줄 출발 후 / 부하 종료 후 잡)에만 → 활동이 없으니 "구간 무활동"이 구조적으로 보장.
  CLAUDE.md "reconcile back after load" 그대로.
- 핫패스에 아무것도 안 더함. 대신 missing 드리프트가 조용한 순간까지 안 고쳐짐(언더셀, 무해하지만 지연).

## 8. 왜 마커를 DB가 아니라 Redis에 두는가 (토론의 결정적 지점)

좌석별 "마지막 선점 시각" 아이디어 자체는 옳다(2회 관측 불필요·좌석 단위 정밀).
관건은 저장처이고, **DB 저장은 3가지 부담** 때문에 부적합:

1. **핫패스 커밋 DB 쓰기 추가.** 롤백이 마커를 지우면 안 되므로 SREM **이전에 독립 커밋** 필요.
   L1(1,000명 한 좌석)에서 그 한 행에 UPDATE가 몰려 **행 잠금 직렬화** → "Redis = 단일 원자 선점점,
   DB는 승자만" 잠긴 설계를 깎아먹음.
2. **패자도 쓴다.** SREM 전에 찍어야 하니 패배 요청도 DB 쓰기 후 SREM 실패 → 낭비.
3. **AUTO(SPOP) 비원자성.** SPOP는 뽑은 뒤에야 좌석 id를 알아 "SREM 전 그 행 찍기"가 불가.
   SPOP 뒤로 미루면 `[SPOP ~ 마커커밋]` 무방비 창. Redis SPOP + DB 쓰기는 두 시스템이라 원자화 불가.

→ Redis 저장 + 선점과 Lua 원자화면 셋 다 해소. (단, 마커 쓰기가 같은 tx면 롤백에 지워진다는 함정은
DB·Redis 공통 원리 — Redis Lua 원자화로 회피.)

## 9. 결정 요약 (확정된 것)

- avail Set만 DB(SoT)로 수렴. 잔여석은 SCARD 파생이라 자동.
- **제거(SREM) = 온라인 상시. 추가(SADD) = "구간 무활동 증명" 있을 때만.**
- 마커를 쓴다면 **Redis에, 선점과 Lua 원자화** (DB 칼럼 아님).
- 폐기: updatedAt 게이트(A), 2회 스냅샷(B).

## 10. 열린 결정 (구현 전 정할 것)

1. **SADD 안전화 기본 전략**: 갈래 1(좌석별 Redis 타임스탬프, 온라인 양방향) vs
   갈래 2(제거전용 + 부하후 SADD).
   - 트레이드오프: 갈래 1은 선점당 Redis 쓰기 1회 상시비용 + 복잡도(Lua), 대신 missing을 온라인 자동 치유
     & 정합성 증명 스토리가 풍부(포트폴리오 평가물에 유리).
     갈래 2는 핫패스 비용 0 & 단순, 대신 missing은 정적 시점까지 지연.
   - 잠정 선호: **갈래 1**(증명/트레이드오프 서술이 평가 산출물이므로). 단 핫패스 비용 0 우선이면 갈래 2.
2. **active:{id} 카운터 reconcile 범위**: T3-10에 포함 vs 별도 follow-up.
   - SoT가 DB 아닌 entry token 집합이라 성격이 다름. 분리 시 T3-10은 avail↔DB에 집중(범위 명확).

## 11. 구현 착수 시 만들/고칠 것 (전략 확정 후 확정)

- `SeatPreemption`: `availableSeatIds(scheduleId)`(SMEMBERS), 단건 보정용 `removeSeat`(SREM) 추가.
  (갈래 1이면 선점/팝을 Lua로 바꾸고 `preemptedAt(scheduleId, seatId)` 조회 추가.)
- `SeatInventoryRepository`: `findAvailableIdsByScheduleId`(id projection — 엔티티 미로드).
- `ReconciliationService`: 스케줄별 avail diff 계산·보정, 드리프트 리포트(added/removed/skipped) 반환.
- `ReconciliationScheduler`(`@Scheduled`, `booking.reconcile.interval` 외부화) + `ReconcileProperties`.
  대상 = 미출발 스케줄. (HeldExpiryScheduler와 동형)
- 드리프트 메트릭 로깅(관측성 = Before/After 산출물).
- 테스트(T3-11 연계): 드리프트 주입(DB AVAILABLE을 Redis에서 SREM / SOLD를 Redis에 SADD) → reconcile
  → `avail Set == DB AVAILABLE` 단언 = 수렴 검증. + in-flight 중 SADD가 안 일어나는 안전성 회귀.

## 12. 관련 항목

- T3-9 / `HeldExpiryService`: 만료 sweep — 같은 "커밋 후·실제 전이 시에만 부수효과" 정합성 패턴.
- T4-13: 만료 sweep 벌크 최적화 — 동일한 SELECT~쓰기 in-flight 함정.
- T3-11: 정합성 통합 테스트에 reconcile 수렴 케이스 포함.
