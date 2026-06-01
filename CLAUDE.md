# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository state

This repo is currently **planning/design only** — there is no application code, build system, or tests yet. Everything lives in `docs/` as Markdown. The first code is scheduled to begin 2026-06-01 (see the development plan). When you add code, you are establishing conventions, not following existing ones.

- **Docs are written in Korean; filenames are English.** Preserve this split when adding or editing docs.
- Each doc starts with header lines (`> 상위 문서: ...`, `> 출처: ...`) that cross-reference sibling docs **by filename**. If you rename a doc, update these references in the other files (grep for the old filename across `docs/`).

## What the project is

A KTX (Korean high-speed rail) ticket-reservation system built as a **portfolio project** to demonstrate concurrency/consistency engineering under load. The engineering core — not a CRUD app — is: prevent oversell when N users contend for the same seat, absorb peak traffic, and **prove correctness + performance with load tests and Before/After numbers**. Success is defined by SLOs and a Definition of Done in the docs, not by feature count.

## Document map (`docs/`)

Read in this order to understand the system:

1. `KTX_Ticketing_Project_Goals.md` — problem statement, evaluation criteria, SLO targets.
2. `KTX_Ticketing_Architecture_and_Verification_Goals.md` — the **Redis-vs-message-queue decision** and measurable verification goals (SLOs S1–S6, load scenarios L1–L6, experiments E1–E4).
3. `KTX_Ticketing_Design.md` — domain model, concurrency strategy, admission control, query/sold-out display, API draft.
4. `KTX_Ticketing_Workflow.md` — end-to-end user-journey sequences (happy path + exception/concurrency paths).
5. `KTX_Ticketing_Development_Plan.md` — 7-week phased plan (P0–P6), milestones M1–M5, MoSCoW scope.
6. `KTX_Ticketing_Task_Checklist.md` — task tracker; status legend `[ ]→[~]→[x]`, `(!)` for blocked.
7. `KTX_Ticketing_Performance_Test.md` — load-test scenarios (k6 / nGrinder).
8. `Portfolio_Project_Evaluation_Criteria.md` — the rubric the project is graded against.

## Locked architectural decisions

These are settled in the design docs. Honor them when implementing — don't silently re-decide:

- **Primary path = Plan A: Redis-synchronous booking.** Booking is request/response (synchronous), correctness via distributed lock + DB optimistic lock. A message queue is used only on the **async side** (payment-result/expiry/stats events), not on the core booking path.
- **Invisible admission control (no visible queue).** Unlike concert ticketing, users see no queue position. The server caps concurrent active sessions at limit `K` via a Redis counter; over the cap returns `429/503 + Retry-After`. Active sessions are tracked by `EntryToken` (TTL). There is intentionally **no** `WaitingQueueEntry`/queue-number entity.
- **Seat state machine is the heart of consistency:** `AVAILABLE → HELD → SOLD`, with `HELD` expiring back to `AVAILABLE` (recommended 5-min TTL) via a scheduler. Sold-out = a Schedule's remaining count is 0.
- **Single atomic preemption point = a Redis Set `avail:{schedule_id}`.** Both booking modes share it: direct seat selection uses `SREM` (returns 1 = won), auto-assignment uses `SPOP`. The winner then does the DB state transition; cancel/expiry returns the seat with `SADD`. Distributed lock (Redisson) is for protecting the DB write critical section and/or as a comparison experiment (E1).
- **Two-tier consistency model — keep these separate:**
  - *Display/query* (schedule list, remaining-seats, sold-out badge) = fast, **eventually consistent**, served from Redis counters / short-TTL cache. Staleness ≤ ~2s is acceptable. Sold-out must be conservative (no false "available").
  - *Booking confirmation* = **strongly consistent**, decided only inside the locked critical section against the DB.
- **DB is the source of truth (SoT);** Redis counters/sets are the fast gate and must reconcile back to the DB after load.
- **Both booking modes are in scope:** `mode=SEAT` (direct) and `mode=AUTO` (auto-assign), sharing the seat inventory.

## Targets implementations must meet (from the docs)

- Consistency (highest priority): **oversell = 0, duplicate reservation = 0** under 1,000 concurrent requests on one seat (L1). This is the M2 risk-gate.
- Booking API p95 ≤ 500ms / p99 ≤ 1s; schedule-list query p95 ≤ 200ms; ≥ 200 TPS at 1,000 VUsers; 5xx < 1% (intended 429/503 excluded).
- Every design choice and SLO value needs a written rationale (trade-off) — that rationale is the graded deliverable, captured in the README.
- Experiments **E1** (lock on/off → oversell vanishes), **E2** (admission control on/off), **E3** (query cache on/off) are required Before/After deliverables; E4 (sync vs async) is optional.

## Stack (확정 — 2026-06-01)

| 항목 | 결정 |
|------|------|
| 앱 프레임워크 | Spring Boot 3.3 (Java 17) |
| 빌드툴 | Gradle 8.8 (Kotlin DSL) |
| DB | MySQL 8.0 |
| Cache / 선점 | Redis 7 |
| 분산락 | Redisson 3.32 |
| 로드테스트 | k6 (우선), nGrinder (대안) |
| CI | GitHub Actions |

### 빌드 / 실행 명령어

```bash
# 로컬 전체 기동 (앱 + MySQL + Redis)
docker compose up --build

# 로컬 개발 (DB/Redis만 Docker, 앱은 IDE에서 실행)
docker compose up mysql redis
# → IDE에서 --spring.profiles.active=local 로 KtxTicketingApplication 실행

# 빌드 (Gradle wrapper 없을 경우 gradle 직접 사용)
./gradlew build          # wrapper 초기화 후
gradle build --no-daemon  # wrapper JAR 없을 때

# 테스트
./gradlew test

# 실행 가능한 JAR 생성
./gradlew bootJar
```

> **Gradle wrapper 초기화**: `gradle-wrapper.jar` 는 바이너리라 별도 초기화 필요.
> 로컬에 Gradle 설치 후 `gradle wrapper --gradle-version 8.8` 실행 → JAR 생성 → 커밋.
> CI는 `gradle/actions/setup-gradle@v3` 로 wrapper 없이도 동작.

### 브랜치 전략

| 브랜치 | 용도 |
|--------|------|
| `main` | 항상 배포 가능한 상태. PR + CI 통과 필수 |
| `develop` | 기능 통합 브랜치. feature/* → develop → main |
| `feature/{task-id}-{desc}` | 기능 개발 (예: `feature/t3-6-booking-seat`) |
| `test/{experiment}` | 실험/PoC 비교 (예: `test/e1-lock-comparison`) |
