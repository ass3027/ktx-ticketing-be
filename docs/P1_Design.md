# P1 설계 확정 (M1 설계 동결)

> 작성일: 2026-06-01
> 상태: **확정** — 이하 결정사항은 P2~P3 구현의 기준

---

## 1. 미정값 결정

| 항목 | 결정값 | 근거 |
|------|--------|------|
| **HELD TTL** | **5분(300초)** | 결제 UX 허용 시간(3~10분 관례)과 좌석 독점 최소화의 균형. 만료 시 스케줄러(T3-9)가 AVAILABLE 복구 |
| **결제 단계** | **HELD→SOLD 2단계** | 결제 실패·타임아웃 시 좌석 복구 로직의 전제조건. Workflow에 이미 설계된 방식 |
| **MQ 도입 범위** | **async side only (P5)** | 코어 예매 경로는 완전 동기식(Plan A). MQ는 알림·통계·만료 이벤트 사이드에만 사용. E4(비교 PoC)는 Could |

---

## 2. ERD

### 관계도
```
train (1) ──< seat (N)           : 열차의 물리 좌석
train (1) ──< schedule (N)       : 열차의 운행편
schedule (1) ──< seat_inventory (N) : 운행편 × 좌석 상태
seat (1) ──< seat_inventory (N)
seat_inventory (1) ──< reservation (0..1) : 예매 레코드
users (1) ──< reservation (N)
```

### 테이블 정의

#### train — 물리적 열차
| 컬럼 | 타입 | 제약 |
|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT |
| name | VARCHAR | NOT NULL |
| train_number | VARCHAR(50) | NOT NULL, UNIQUE |

#### seat — 열차의 물리 좌석
| 컬럼 | 타입 | 제약 |
|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT |
| train_id | BIGINT | FK → train.id |
| car_number | INT | NOT NULL |
| seat_number | VARCHAR(10) | NOT NULL |
| — | — | UNIQUE(train_id, car_number, seat_number) |

#### schedule — 운행편 (특정 날짜·시각)
| 컬럼 | 타입 | 제약 |
|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT |
| train_id | BIGINT | FK → train.id |
| departure_station | VARCHAR(50) | NOT NULL |
| arrival_station | VARCHAR(50) | NOT NULL |
| departure_time | DATETIME | NOT NULL |
| arrival_time | DATETIME | NOT NULL |
| total_seats | INT | NOT NULL |

#### seat_inventory — 핵심 테이블 (운행편 × 좌석 상태)
| 컬럼 | 타입 | 제약 |
|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT |
| schedule_id | BIGINT | FK → schedule.id |
| seat_id | BIGINT | FK → seat.id |
| status | ENUM(AVAILABLE, HELD, SOLD) | NOT NULL, DEFAULT AVAILABLE |
| version | INT | NOT NULL, DEFAULT 0 — **낙관적 락 키** |
| held_at | DATETIME | NULL |
| expires_at | DATETIME | NULL |
| — | — | UNIQUE(schedule_id, seat_id) |
| — | — | INDEX(schedule_id, status) — reconcile 쿼리 최적화 |

> `version` 컬럼이 낙관적 락의 핵심. 동시 update 시 version 불일치 → `OptimisticLockException` → 재시도.

#### reservation — 예매 레코드
| 컬럼 | 타입 | 제약 |
|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT |
| user_id | BIGINT | FK → users.id |
| seat_inventory_id | BIGINT | FK → seat_inventory.id, UNIQUE |
| status | ENUM(HELD, CONFIRMED, CANCELLED, EXPIRED) | NOT NULL |
| held_at | DATETIME | NOT NULL |
| expires_at | DATETIME | NOT NULL — HELD TTL 기반 |
| confirmed_at | DATETIME | NULL |
| cancelled_at | DATETIME | NULL |

#### users — 사용자
| 컬럼 | 타입 | 제약 |
|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT |
| email | VARCHAR(255) | NOT NULL, UNIQUE |
| name | VARCHAR(100) | NOT NULL |
| created_at | DATETIME | NOT NULL, 자동 설정 |

---

## 3. Redis 키 설계

| 키 패턴 | 자료구조 | 값 | TTL | 용도 |
|---------|---------|-----|-----|------|
| `avail:{schedule_id}` | Set | seat_inventory_id 집합 | 없음(명시적 관리) | 원자적 선점 게이트. SEAT→`SREM`, AUTO→`SPOP` |
| `remain:{schedule_id}` | String(int) | 잔여 좌석 수 | 없음(명시적 관리) | 조회 화면 표시 (약한 일관성, staleness ≤ 2s) |
| `active:{schedule_id}` | String(int) | 현재 활성 세션 수 | 없음(명시적 관리) | 입장 제어 상한 K 비교. K 초과 시 429/503 |
| `entry:{token_uuid}` | String(JSON) | `{userId, scheduleId}` | **10분** | EntryToken 검증. TTL 만료 = 세션 종료 |

### 일관성 모델
- `avail` · `remain` · `active`: **DB가 SoT**. Redis는 빠른 게이트. 주기적 reconcile(T3-10)로 DB 수렴.
- `entry`: Redis only (DB 저장 불필요). TTL 만료가 곧 세션 만료.

### 선점 흐름
```
mode=SEAT:  SREM avail:{id} {seat_inventory_id}  → 반환값 1=선점 성공, 0=실패
mode=AUTO:  SPOP avail:{id}                       → seat_inventory_id 반환, nil=매진
```

---

## 4. Seed 데이터 (T1-6)

| 테이블 | 건수 | 내용 |
|--------|------|------|
| train | 1 | KTX 경부선 (KTX-001) |
| seat | 1,000 | car 1~20, 각 50석 (1A~50D) |
| schedule | 50 | 2026-07-01~2026-08-19, 서울→부산 08:00 |
| seat_inventory | 50,000 | 전 편 전 좌석 AVAILABLE |
| users | 10,000 | user1@ktx.test ~ user10000@ktx.test |

생성 시간: **1.1초** (JdbcTemplate batch, MySQL rewriteBatchedStatements)

---

## 5. 엔티티 클래스 목록

```
src/main/java/com/ktx/ticketing/
├── domain/
│   ├── SeatStatus.java          (enum: AVAILABLE, HELD, SOLD)
│   ├── ReservationStatus.java   (enum: HELD, CONFIRMED, CANCELLED, EXPIRED)
│   ├── Train.java
│   ├── Seat.java
│   ├── Schedule.java
│   ├── SeatInventory.java       ← 낙관적 락(@Version), 상태 전이 메서드
│   ├── Reservation.java         ← 정적 팩토리 hold(), confirm(), cancel(), expire()
│   └── User.java
└── infra/
    └── DataInitializer.java     ← @Profile("local"), 50,000건 시드
```
