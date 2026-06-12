# KTX Ticketing

> **명절 KTX 표 예매의 대규모 동시접속·매진 경쟁 문제를, 정합성을 보장하는 선착순 예매로 푼다.**

명절·연휴에 수만 명이 동시에 같은 좌석을 예매하려 할 때 발생하는 **초과 판매(oversell)·중복 예매·좌석 재고 깨짐**을 막고, 그 정합성과 성능을 **부하 테스트와 Before/After 수치로 증명**하는 것을 목표로 하는 포트폴리오 프로젝트다.

CRUD 기능 수가 아니라, **N명이 한 좌석을 경합할 때 정확히 1명만 성공하게 만드는 동시성·정합성 엔지니어링**이 이 프로젝트의 핵심이다.

---

## 왜 이 문제인가

명절마다 KTX 예매창에서 좌석을 골라 결제 직전에 "이미 매진" 처리되거나, 새로고침 지옥을 겪으며 *왜 동시 예매가 이렇게 깨지는가*가 궁금했다. 이 프로젝트는 그 질문을 공학적으로 정면 돌파한다:

- **문제 정의** — N명이 같은 좌석을 동시에 예매 시도할 때, 단 1명만 성공하고 좌석 재고가 정확히 차감되어야 한다.
- **접근** — 분산 락 + DB 낙관 락으로 경합을 제어하고, 비가시 입장 제어로 피크 트래픽을 흡수한다.
- **증명** — 모든 설계 결정에 트레이드오프 근거를 남기고, 부하 테스트로 SLO 충족 여부를 수치화한다.

---

## 핵심 목표

| 우선순위 | 목표 | 기준 |
|---------|------|------|
| **최우선** | 정합성 | 동시 1,000 요청 / 단일 좌석에서 **초과 판매 0건, 중복 예매 0건** (M2 리스크 게이트) |
| 높음 | 응답 성능 | 예매 API **p95 ≤ 500ms / p99 ≤ 1s**, 운행 조회 **p95 ≤ 200ms** |
| 높음 | 처리량 | 동시 **1,000 VUser에서 ≥ 200 TPS** |
| 중간 | 가용성 | 부하 중 5xx **< 1%** (의도된 429/503 제외) |
| 결과물 | 증명 | E1~E3 **Before/After 수치** + 모든 SLO에 "왜 이 값인가" 근거 문서화 |

---

## 아키텍처 결정

> **주 경로 = Plan A: Redis 동기 예매 + 비가시 입장 제어.**
> 메시지 큐는 핵심 예매 경로가 아니라 **비동기 사이드**(결제 결과·만료·통계 이벤트)에만 도입한다.

### 결정 근거 (Redis 동기 vs 메시지 큐 비동기)

| 기준 | Plan A (Redis 동기) — **채택** | Plan B (MQ 비동기) |
|------|-------------------------------|--------------------|
| 정합성 보장 | 분산 락 + 낙관 락 | 파티션 직렬화 |
| 응답 UX | 단순 (요청-응답 1회) | 복잡 (추적/폴링) |
| 운영 복잡도 | 낮음 | 높음 |
| 학습/어필 | **락 설계·경합 제어** | 스트림·백프레셔·멱등성 |

KTX 예매의 본질 난제는 "같은 좌석 동시 점유 제어"이므로, **분산 락으로 정면 돌파**하는 것이 학습·어필 가치가 가장 크다. 실제 KTX 앱처럼 **순번을 노출하지 않는 입장 제어**가 도메인에도 충실하다. (상세: [docs/KTX_Ticketing_Architecture_and_Verification_Goals.md](docs/KTX_Ticketing_Architecture_and_Verification_Goals.md))

### 핵심 메커니즘

- **단일 원자 선점 지점 = Redis Set `avail:{schedule_id}`** — 직접 선택은 `SREM`(반환 1 = 승리), 자동 배정은 `SPOP`. 승자만 DB 상태 전이를 수행하고, 취소/만료는 `SADD`로 좌석을 되돌린다.
- **좌석 상태 기계** — `AVAILABLE → HELD → SOLD`, `HELD`는 TTL(5분) 만료 시 스케줄러가 `AVAILABLE`로 복구.
- **비가시 입장 제어** — Redis 활성자 카운터로 동시 활성 세션을 상한 `K`로 제한, 초과 시 `429/503 + Retry-After`. 보이는 대기열은 없다.
- **2단 일관성 모델** — *조회/표시*(운행 리스트·잔여석·매진)는 빠르고 **결과적 일관성**(staleness ≤ 2s), *예매 확정*은 락 임계영역 안에서만 결정되는 **강한 일관성**. **DB가 SoT**, Redis는 빠른 게이트.

> **인증/인가는 이번 범위 외(out of scope).** 이 프로젝트의 평가 핵심은 동시성·정합성이며, 사용자 신원은 입장 토큰(`EntryToken`)으로만 다룬다. 실제 로그인/회원 인증(Spring Security 등)은 부하 테스트를 단순하게 유지하기 위해 의도적으로 제외했다.

### 시스템 논리도

```
[Client]
  │ ⓪ 운행 리스트/매진 조회 (읽기 多, 약한 일관성)
  ▼
┌──────────┐ ──조회──▶ ┌──────────────────────────────┐
│  API 서버 │           │ Redis: 잔여 카운터 / TTL 캐시 │
└────┬─────┘           └──────────────────────────────┘
  │ ① 입장 토큰 요청
  ▼
┌─────────────────────────────┐
│ Redis: 활성자 카운터(상한 K) │ → 초과 시 429/503 + Retry-After (순번 X)
└──────────────┬──────────────┘
  │ ② 토큰 보유자만 동기 예매
  ▼
┌──────────┐   좌석 선점   ┌──────────────────────────┐
│  예매 처리 │ ──────────▶ │ Redis Set / 분산 락        │
└────┬─────┘   임계영역    └──────────────────────────┘
     │ 상태전이(강한 일관성)
     ▼
┌──────────────────────────────┐      ┌────────────────────────┐
│ DB (SoT): Schedule /          │      │ Message Queue (비동기)  │
│ SeatInventory / Reservation   │ ───▶ │ 결제결과·만료·통계 이벤트 │
└──────────────────────────────┘      └────────────────────────┘
        ▲   │ 예매 확정 시 Redis 잔여 카운터 동기화
        │ HELD TTL 만료 스케줄러 → 미결제 좌석 자동 복구 → 카운터 복원
```

---

## SLO (검증 목표)

| # | 지표 | 목표 기준 | 근거 |
|---|------|-----------|------|
| S1 | 예매 API 응답 | p95 ≤ 500ms, p99 ≤ 1s | Google SRE 일반 웹 SLO 관례 |
| S2 | 운행 리스트/매진 조회 | p95 ≤ 200ms | 모든 사용자의 진입점, 읽기 多 |
| S2b | 매진/잔여석 표시 staleness | ≤ 2초 | 약한 일관성 허용 범위 |
| S3 | 처리량 | 동시 1,000 VUser에서 ≥ 200 TPS | 명절 피크 가정 |
| S4 | **정합성 (최우선)** | **초과 판매 0건 / 중복 예매 0건** | 예매 시스템 절대 조건 |
| S5 | 가용성 | 부하 중 5xx < 1% (의도된 429/503 제외) | 피크에도 서비스 유지 |
| S6 | 임계점 | 시스템이 무너지는 동시 사용자 수를 숫자로 파악 | "어디서 무너지는가" |

> 기준값은 1차 측정 후 보정한다. 중요한 건 **"왜 이 값인가"를 설명할 수 있는 것**. (시나리오 L1~L6: [docs](docs/KTX_Ticketing_Architecture_and_Verification_Goals.md))

---

## Before / After 실험 (포트폴리오 하이라이트)

| 실험 | Before | After | 보여줄 것 | 상태 |
|------|--------|-------|-----------|------|
| **E1** 동시성 제어 | 락 없음 | 분산 락 적용 | 초과 판매 발생 → **0건** | 측정 예정 |
| **E2** 입장 제어 | 제어 없음 | 활성자 수 제한 | 5xx 폭증 → 429/503 흡수 | 측정 예정 |
| **E3** 조회 캐시 | 매 요청 DB 집계 | Redis 카운터/캐시 | 조회 p95·DB 부하 대폭 감소 | 측정 예정 |
| E4 (선택) | Redis 동기 | MQ 비동기 | 처리량/지연/정합성 수치 비교 | 선택 |

> E1·E2·E3는 필수. 각 실험은 그래프 + 한 줄 해석으로 정리한다. **측정값은 확보되는 대로 이 표에 채운다.**

---

## 기술 스택

| 항목 | 결정 |
|------|------|
| 앱 프레임워크 | Spring Boot 4.0 (Java 25) |
| 빌드툴 | Gradle 9.5 (Kotlin DSL) |
| DB | MySQL 8.0 |
| Cache / 선점 | Redis 7 |
| 분산락 | Redisson 4.4 |
| 로드테스트 | k6 (우선), nGrinder (대안) |
| 통합 테스트 | Testcontainers (MySQL/Redis 자동 기동) |
| CI | GitHub Actions |

---

## 빌드 / 실행

```bash
# 로컬 전체 기동 (앱 + MySQL + Redis)
docker compose up --build

# 로컬 개발 (DB/Redis만 Docker, 앱은 IDE에서 실행)
docker compose up mysql redis
# → IDE에서 --spring.profiles.active=local 로 KtxTicketingApplication 실행

# 빌드 / 테스트
./gradlew build
./gradlew test

# 실행 가능한 JAR
./gradlew bootJar
```

> 통합 테스트는 Testcontainers가 MySQL/Redis를 자동 기동하므로 로컬 인프라가 떠 있지 않아도 된다 (Docker 데몬은 필요).

---

## 문서 맵 (`docs/`)

이 순서로 읽으면 시스템을 이해할 수 있다 (문서는 한국어, 파일명은 영어):

1. [KTX_Ticketing_Project_Goals.md](docs/KTX_Ticketing_Project_Goals.md) — 문제 정의, 평가 기준, SLO 목표
2. [KTX_Ticketing_Architecture_and_Verification_Goals.md](docs/KTX_Ticketing_Architecture_and_Verification_Goals.md) — Redis vs MQ 결정, SLO S1~S6, 시나리오 L1~L6, 실험 E1~E4
3. [KTX_Ticketing_Design.md](docs/KTX_Ticketing_Design.md) — 도메인 모델, 동시성 전략, 입장 제어, API 초안
4. [KTX_Ticketing_Workflow.md](docs/KTX_Ticketing_Workflow.md) — 사용자 여정 시퀀스 (정상/예외/경합 경로)
5. [KTX_Ticketing_Development_Plan.md](docs/KTX_Ticketing_Development_Plan.md) — 7주 단계별 계획 (P0~P6), 마일스톤 M1~M5
6. [KTX_Ticketing_Task_Checklist.md](docs/KTX_Ticketing_Task_Checklist.md) — 태스크 트래커
7. [KTX_Ticketing_Performance_Test.md](docs/KTX_Ticketing_Performance_Test.md) — 부하 테스트 시나리오 (k6 / nGrinder)
8. [Portfolio_Project_Evaluation_Criteria.md](docs/Portfolio_Project_Evaluation_Criteria.md) — 평가 루브릭
