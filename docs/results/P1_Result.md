# P1 설계 확정 결과 (M1 달성)

> 작성일: 2026-06-01
> DoD: ERD/DDL/Redis 키/seed 확정 = 설계 동결 ✅

---

## 확정된 미정값

| 항목 | 결정 | 근거 |
|------|------|------|
| HELD TTL | **5분(300초)** | 결제 UX 허용 시간과 좌석 독점 최소화의 균형 |
| 결제 단계 | **HELD→SOLD 2단계** | 만료 복구 로직의 전제조건. Workflow에 이미 설계된 방식 |
| MQ 도입 범위 | **async side only (P5)** | 코어 예매 경로는 완전 동기식(Plan A). MQ는 알림·통계 사이드에만 |

---

## JPA 엔티티 (T1-4)

| 클래스 | 역할 | 핵심 |
|--------|------|------|
| `Train` | 물리 열차 | trainNumber UNIQUE |
| `Seat` | 열차의 물리 좌석 | UNIQUE(train, car, seatNumber) |
| `Schedule` | 운행편 (날짜·시각) | departureTime, totalSeats |
| `SeatInventory` | 운행편 × 좌석 상태 | `@Version`(낙관적 락), UNIQUE(schedule, seat), INDEX(schedule, status) |
| `Reservation` | 예매 레코드 | 정적 팩토리 `hold()`, `confirm()`, `cancel()`, `expire()` |
| `User` | 사용자 | email UNIQUE, `@CreationTimestamp` |
| `SeatStatus` | enum | AVAILABLE / HELD / SOLD |
| `ReservationStatus` | enum | HELD / CONFIRMED / CANCELLED / EXPIRED |

### ERD 관계
```
train ──< seat
train ──< schedule
schedule ──< seat_inventory >── seat   ← 핵심 교차 테이블
users ──< reservation >──────── seat_inventory
```

---

## Redis 키 설계 (T1-5)

| 키 | 타입 | TTL | 용도 |
|----|------|-----|------|
| `avail:{schedule_id}` | Set | 없음 | 원자적 선점 (SREM/SPOP) |
| `remain:{schedule_id}` | String | 없음 | 잔여석 표시 (약한 일관성) |
| `active:{schedule_id}` | String | 없음 | 입장 제어 상한 K |
| `entry:{token_uuid}` | String | 10분 | EntryToken 검증 |

---

## Seed DataInitializer (T1-6)

| 테이블 | 건수 | 내용 |
|--------|------|------|
| train | 1 | KTX 경부선 (KTX-001) |
| seat | 1,000 | car 1~20, 각 50석 |
| schedule | 50 | 2026-07-01~2026-08-19, 서울→부산 |
| seat_inventory | 50,000 | 전 편 전 좌석 AVAILABLE |
| users | 10,000 | user1@ktx.test ~ user10000@ktx.test |

- 생성 시간: **1.1초** (JdbcTemplate batch + `rewriteBatchedStatements=true`)
- 실행 조건: `@Profile("local")`, 이미 데이터 있으면 skip (idempotent)

---

## 테스트 (9개)

| 클래스 | 수 | 검증 내용 |
|--------|-----|-----------|
| `SeatInventoryTest` | 3 | hold/confirm/release 상태 전이 + 타임스탬프 |
| `ReservationTest` | 5 | 상태 전이 + **HELD TTL 5분 수치 검증** (T1-1) |
| `KtxTicketingApplicationTests` | 1 | 스모크 |

> `JpaSchemaTest` 제거 — H2 ≠ MySQL, Docker 실검증으로 대체

---

## 생성/수정 파일

| 파일 | 분류 |
|------|------|
| `src/main/java/.../domain/*.java` (8개) | 엔티티 + enum |
| `src/main/java/.../infra/DataInitializer.java` | seed runner |
| `src/test/java/.../domain/SeatInventoryTest.java` | 단위 테스트 |
| `src/test/java/.../domain/ReservationTest.java` | 단위 테스트 |
| `src/test/resources/application.yml` | 테스트 설정 |
| `build.gradle.kts` | Lombok + H2 추가 |
| `application.yml` | open-in-view, dialect 제거 |
| `application-local.yml` | batch 설정 추가 |
| `docker-compose.yml` | `SPRING_PROFILES_ACTIVE=local` 추가 |
| `docs/P1_Design.md` | 설계 결정 문서 |
