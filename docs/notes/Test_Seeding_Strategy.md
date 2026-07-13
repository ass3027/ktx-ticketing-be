# KTX Ticketing — 데이터 시드 전략: JPA vs JdbcTemplate

> 상위 문서: CLAUDE.md (테스트 규칙), KTX_Ticketing_Design.md
> 출처: KTX_Ticketing_Task_Checklist.md (T3-11 선결), 시드 전략 토론 2026-06-11
> 상태: **확정(2026-06-11)** — 테스트 시드 = JPA, 대량 운영 시드(DataInitializer) = JdbcTemplate

`local→test` 프로파일 전환 후처리 중, 테스트 시드를 JdbcTemplate 에서 JPA 로 바꾸며
도출한 결정. "데이터 초기화는 무조건 JPA 로 통일" 같은 단순 일관성 규칙이 왜 틀리는지,
그리고 **성능 격차의 진짜 원인**을 기록한다. 같은 논의를 반복하지 않기 위함.

---

## 1. 결론 (한 줄)

데이터 초기화 방식은 **규모와 목적**으로 갈린다 — 같은 "시드"라도 판단 기준이 다르다.

| 용도 | 방식 | 위치 |
|------|------|------|
| **소량 + 불변식 정확성** (테스트 시드) | **도메인 엔티티 + JPA** | `ConcurrencyPocTest`, `ReconciliationIntegrationTest` |
| **대량 + 적재 속도** (운영/부하 시드) | **JdbcTemplate batch** | `DataInitializer`(@Profile("local"), seat_inventory 50k) |

## 2. 테스트 시드를 JPA 로 한 이유

JdbcTemplate 직접 INSERT 는 **도메인 모델을 우회**하므로, 엔티티 라이프사이클이
보장하던 불변식을 손으로 재현해야 한다. 실제로 JDBC 시도가 두 번 깨졌다:

- `users.created_at` NOT NULL 위반 — `@CreationTimestamp`(`User.createdAt`)는 JPA
  save 시 채워지지만 JDBC 는 Hibernate 를 우회해 빈 채로 INSERT.
- `seat_inventory.seat_id` FK 위반 — `seat` 부모 행을 수동으로 챙기지 않아 발생.
  (게다가 이 FK 위반이 뜬다는 건 Hibernate 가 `create-drop` 에서 FK 제약을 실제로
  **생성**한다는 확증이었다.)

JPA(`em.persist` + 도메인 생성자)는 `@CreationTimestamp`·FK 정합성·`@Version` 을
공짜로 보장하고, 시드가 도메인 모델과 한 소스라 **모델 변경 시 컴파일러가 깨짐을 잡는다.**
소량(좌석 1개 + user 1만)이라 아래 §4 의 JPA 비용은 무시할 수준.

## 3. 대량 운영 시드를 JdbcTemplate 으로 두는 이유

`DataInitializer` 는 seat_inventory **50,000건** + user 1만을 적재한다(P4 부하테스트용).
여기선 적재 속도가 핵심이고, JDBC batch 라서 **1.1초**에 끝난다(P1_Result 기록).
`created_at`/`version` 은 의도적으로 명시 주입하므로 불변식 우회 문제도 없다.

## 4. 성능 격차의 진짜 원인 (핵심 기록)

> "JPA 라서 INSERT 가 느리다"는 **부정확**하다. 정확한 원인은 아래 둘로 분해된다.

### 요소 A — Multi-row INSERT 재작성 (왕복 수) → JPA/JDBC 무관
- `rewriteBatchedStatements=true` 가 있으면 batch 의 N개 INSERT 가
  `INSERT ... VALUES (..),(..),(..)` **한 문장**으로 재작성 → 5000건 = **1 왕복**.
  없으면 5000건 = 5000 왕복(원격 DB 에서 수백 배 차이). "1.1초"의 핵심.
- **JPA(Hibernate)도** `hibernate.jdbc.batch_size` + `order_inserts` 를 켜면 같은
  multi-row 재작성을 한다(`application-local.yml` 에 이미 `batch_size: 1000`,
  `order_inserts: true` 존재). 즉 **왕복 수는 배치만 켜면 JPA·JDBC 동등** — 격차 요인 아님.

### 요소 B — 영속성 컨텍스트(1차 캐시) 누적 → JPA 고유 비용 (진짜 원인)
- `em.persist` 한 엔티티는 영속성 컨텍스트에 **계속 쌓인다.** 5만 엔티티를 한 트랜잭션에서:
  - **메모리**: 5만 객체 + dirty checking 용 스냅샷(복사본) → 힙 압박, OOM 가능.
  - **flush 비용**: flush 마다 컨텍스트 내 **전체 엔티티 dirty check**(스냅샷 비교) → O(N²) 경향.
- 회피하려면 배치마다 `em.flush()` + **`em.clear()`** 로 컨텍스트를 비워야 한다(보일러플레이트).
- JDBC `batchUpdate` 는 영속성 컨텍스트가 **없어** 이 비용이 원천적으로 0 —
  `Object[]` 만 넘기니 dirty checking·스냅샷·clear 가 전부 불필요.

### 요약표
| 요소 | JDBC batch | JPA (배치 켬) | 격차의 본질 |
|------|-----------|--------------|------------|
| INSERT 왕복 수 | 적음(multi-row) | 적음(multi-row) | **동등** — `rewriteBatchedStatements` 가 결정 |
| 영속성 컨텍스트 | 없음 | 누적(clear 안 하면 O(N²)+OOM) | **JPA 고유 추가 비용** |
| dirty checking/스냅샷 | 없음 | 엔티티당 | **JPA 고유 추가 비용** |

**핵심 한 줄**: 대량 적재에서 JDBC 가 우위인 진짜 이유는 왕복 수가 아니라(배치로 동등하게 만들 수 있음)
**영속성 컨텍스트 누적 비용이 없다**는 점이다.

## 5. 시드 멱등성 함정 (부수 기록)

테스트 시드를 컨텍스트당 1회로 만들 때, 멱등 판단을 **인스턴스 필드**로 하면 안 된다 —
JUnit 은 테스트 메서드마다 클래스 인스턴스를 새로 만들어 필드가 null 로 리셋되지만,
`create-drop` DB·컨텍스트 캐시는 클래스 전체에서 1세트만 유지된다. → "이미 시드됨"은
**DB 존재 여부**로 판정해야 두 번째 메서드의 중복 INSERT(UNIQUE 충돌)를 막는다.
(`ReconciliationIntegrationTest` 처럼 메서드별 독립 데이터가 필요하면 UNIQUE 컬럼을
메서드마다 고유 값으로 부여.)
