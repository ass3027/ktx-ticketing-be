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

## Stack (not yet finalized)

**Redis is confirmed.** Proposed but undecided: Spring Boot + MySQL, local Docker Compose (app + DB + Redis), k6 (preferred) or nGrinder for load tests. Confirm the stack before scaffolding (this is part of phase P0/M1). Once a build tool exists, document its build/test/run commands here.
