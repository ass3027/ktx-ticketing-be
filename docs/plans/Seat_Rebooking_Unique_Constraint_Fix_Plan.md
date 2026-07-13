> 상위 문서: `KTX_Ticketing_Design.md`
> 출처: 2026-06-21 세션 — L4 재측정(슬롯 churn 모델) 중 발견한 "되돌아온 좌석 재예매 불가" 버그
> 상태: 계획(미착수). 착수 시 별도 브랜치 + 계획 승인 절차.

# 좌석 재예매 불가 버그 수정 계획 (reservation.seat_inventory_id 유니크 제약)

## 0. 한 줄 요약

`Reservation.seat_inventory_id` 의 **상태-무관 전역 유니크 제약** 때문에, 취소/만료로 되돌아온
좌석을 다시 예매하면 `Duplicate entry` → **500** 이 난다. 제약의 의미를 "**활성(HELD/CONFIRMED)
예약만 좌석당 1건**"으로 좁혀(또는 제거해) 재예매를 정상화하고, 회귀 테스트로 못박는다.

---

## 1. 버그 (무엇이 / 어디서)

`src/main/java/com/ktx/ticketing/domain/Reservation.java:34`
```java
@JoinColumn(name = "seat_inventory_id", nullable = false, unique = true)
```
→ DB 에 `reservation.seat_inventory_id` 전역 유니크 인덱스(`UKqjf4pl71kkc4ifucnr5ycpnbf`) 생성.
즉 **좌석당 reservation 행이 영원히 1개**.

취소(`DELETE /api/reservations/{id}`)·만료(HELD TTL sweep)는 행을 **삭제하지 않고 상태만**
CANCELLED/EXPIRED 로 바꾸고 좌석을 avail 로 되돌린다(`SADD`). 그래서:
```
book 좌석X (INSERT reservation) → cancel (행 잔존 + 좌석 avail 복귀)
→ SPOP 가 X 재배정 → INSERT reservation(seat_inventory_id=X)
→ Duplicate entry 'X' for key UK... → DataIntegrityViolationException → 500
```

### 의도했어야 할 불변식
> 한 좌석에 **동시에 활성(HELD/CONFIRMED)인** 예약은 1건. 취소/만료된 과거 예약은 제외.

현재 제약은 *상태를 무시한* 전역 유니크라 이 불변식보다 과하게 강해서, 취소/만료 행이 좌석을
영구 점유해 재예매를 막는다.

### 영향 범위 — L4 한정이 아님
churn(book→cancel→rebook)이 처음으로 "되돌아온 좌석 재예매"를 시켜 드러났을 뿐, **논리적으로
HELD TTL 만료(T3-9) 경로도 동일하게 깨진다**: HELD 만료 → 좌석 AVAILABLE → 그 좌석을 다음
사람이 예매 → 같은 유니크 충돌 → 500. = "만료/취소된 좌석은 영영 재예매 불가."
기존 단위·통합 테스트가 *되돌아온 좌석을 다시 예매하는* 케이스를 안 만들어서 미검출.

### 증거 (2026-06-21 L4 1회차, K=100, 500 TPS, 4분)
- `server_errors: 100` (threshold `<10` 위반 → k6Exit=99)
- app 로그: `DataIntegrityViolationException: Duplicate entry '771'/'635'/'271'... for key 'reservation.UKqjf4pl71kkc4ifucnr5ycpnbf'` (중복 값 = seat_inventory_id)
- 부작용: 5xx 100건이 슬롯 100개를 누수(아래 §4-2) → K 조기 포화 → 입장 517건뿐

---

## 2. 스키마 적용 방식의 제약 (반드시 인지)

- 스키마는 **Hibernate `ddl-auto`** 로 생성. `application.yml`=`none`, `application-local.yml`=`update`.
  부하 테스트/도커는 **local 프로파일(`update`)** 사용.
- **Flyway/Liquibase 없음.** `db/migration` 디렉터리 없음.
- **함정: `ddl-auto: update` 는 컬럼/제약을 추가만 하고 절대 삭제하지 않는다.**
  → 엔티티에서 `unique=true` 만 떼도, *기존 DB* 의 유니크 인덱스는 그대로 남는다.
  → 따라서 어떤 안을 택하든 **기존 스키마에 명시적 DDL(ALTER) 또는 스키마 재생성**이 필요하다.
  - 스키마 재생성 = MySQL 볼륨 드롭(`docker compose down -v`) 후 ddl-auto 가 엔티티대로 재생성.
    부하 테스트 reset 은 TRUNCATE 라 스키마는 안 건드린다는 점에 주의.

---

## 3. 수정 선택지

### A-1. 유니크 제약 제거 (최소)
오버셀 방어를 상위 계층에 일임: **Redis 선점(SREM/SPOP, 좌석당 승자 1명)** + **seat_inventory
낙관 락(@Version)·상태머신(AVAILABLE→HELD)**. reservation 유니크는 이 둘과 중복된 최후 방어선.
- 변경: 엔티티에서 `unique = true` 제거 + 기존 인덱스 드롭(`ALTER TABLE reservation DROP INDEX UK...`)
  또는 스키마 재생성.
- 장점: 가장 단순. 즉시 해소.
- 단점: **DB 레벨 최후 방어선 상실** → "오버셀=0 을 다층 방어로 증명" 서사가 한 겹 얇아짐.

### A-2. "활성 한정" 부분 유니크 (★ 권장)
MySQL 은 부분 인덱스(`WHERE status IN ...`)가 없으므로 **생성 컬럼(generated column)** 으로 우회:
```sql
ALTER TABLE reservation
  ADD COLUMN active_seat_inventory_id BIGINT
    AS (IF(status IN ('HELD','CONFIRMED'), seat_inventory_id, NULL)) STORED,
  ADD UNIQUE KEY uk_active_seat (active_seat_inventory_id);
-- 기존 전역 유니크 제거
ALTER TABLE reservation DROP INDEX UKqjf4pl71kkc4ifucnr5ycpnbf;  -- 실제 인덱스명 확인 후
```
MySQL 유니크 인덱스는 **NULL 중복 허용** →
- 활성(HELD/CONFIRMED): 값 존재 → 좌석당 1건 강제 (오버셀 DB 방어 **유지**)
- 취소/만료: NULL → 공존 허용 → **재예매 가능**
- 장점: 재예매 정상화 **+** DB 최후 방어선 유지. 생성 컬럼이라 앱이 마커를 관리 불필요(자동).
- 단점: ddl-auto 로 표현 불가 → **명시적 DDL 스크립트 필요**. 엔티티는 파생 컬럼을 읽기전용
  (`insertable=false, updatable=false`)으로 매핑하거나 미매핑.

### (기각) A-3 취소 행 UPDATE 재사용 / A-4 취소·만료 행 하드삭제
- A-3: 예약은 사용자 소유 → user_id 덮어쓰면 의미·이력 파괴.
- A-4: 감사/이력 상실 + 경합 위험.

---

## 4. 구현 단계 (A-2 기준)

### 4-1. 스키마 변경
1. 현재 인덱스명 확인: `SHOW INDEX FROM reservation;` (생성명 `UKqjf4...` 는 환경마다 다를 수 있음).
2. 위 DDL 적용. **적용 위치 결정 필요** — 도구가 없으므로 택1:
   - (간단) 스키마 재생성 경로: 엔티티에서 `unique=true` 제거 + `down -v` 재생성 후, 생성 컬럼만
     별도 init SQL 로 추가. 단 ddl-auto 가 생성 컬럼을 못 만드니 init SQL 이 매 신규 스키마에 필요.
   - (정공법) **경량 마이그레이션 도입 검토**(Flyway). 이 프로젝트 규모에서 향후 스키마 변경을 위해
     도입 가치 있음 → 별도 판단. 도입 시 V_ 스크립트로 위 DDL 관리.
3. `DataInitializer`/seed 가 reservation 을 직접 INSERT 하지 않으므로 시드 영향 없음(확인).

### 4-2. 엔티티
- `Reservation.java:34` `@JoinColumn(... unique = true)` → `unique` 제거.
- (A-2) 파생 컬럼은 매핑하지 않거나 `@Column(name="active_seat_inventory_id", insertable=false, updatable=false)`
  읽기전용으로만. 도메인 로직은 이 컬럼을 신경 쓰지 않는다(DB 가 자동 유지).

### 4-3. (부수 발견) 입장 슬롯 누수 — 별도 점검
입장(`active` INCR) 후 **예매 자체가 실패**하면 슬롯을 되돌릴 경로가 없다(`AdmissionService.leave`
는 예약 confirm/cancel/만료에서만 호출 — 예약이 안 생기면 트리거 없음). 이번엔 5xx 100건이 슬롯
100개를 누수시켰다. 제약을 고치면 5xx 가 사라져 이 누수는 안 터지지만, **"입장했으나 예매 실패"
일반 케이스의 슬롯 회수**(예: EntryToken TTL 만료 시 leave, 또는 예매 실패 응답 경로에서 보상)는
독립적으로 점검할 가치가 있다. → 백로그 별도 항목 권장(이번 수정 범위에 묶을지 결정).

### 4-4. 회귀 테스트 (필수)
`BookingIntegrationTest`(또는 T3-11)에 케이스 추가:
1. **되돌아온 좌석 재예매 성공**: 좌석 예매 → 취소 → **같은 좌석 재예매가 201** (현재는 500).
2. **만료 후 재예매 성공**: HELD → 만료 sweep → 같은 좌석 재예매 201.
3. **활성 동시성 불변식 유지**: 한 좌석에 활성 HELD 2건은 여전히 차단(A-2 의 부분 유니크가 막는지).
> 이 테스트가 없어서 여태 미검출 — 추가가 재발 방지의 핵심.

### 4-5. 검증 (L4 재측정)
- `pwsh load-tests/scripts/Run-Scenario-Container.ps1 -Scenario load-tests/scenarios/L4_admission_overload.js -AdmissionMax 100 -Iterations 1`
- 기대: `server_errors < 10`(5xx 소멸) · `admission_reject_rate > 0.5` 유지 ·
  reserve 표본이 3분 내내(입장 수백→수천) · `dropped_iterations==0` · k6Exit=0.
- 통과하면 `-Iterations 3` 본 측정 → `docs/results/P4_Result.md` T4-6 기입.

---

## 5. 착수 시점의 미커밋 작업 트리 상태 (이 세션에서 만든 것)

아래는 **아직 커밋 안 됨**. 이 버그 수정과 함께 처리:
- `load-tests/scenarios/L4_admission_overload.js` — L4 하드닝 ①②③b 적용
  (admission_reject_rate>0.5, responseCallback+http_req_failed{type:entry}, cancel churn + think time).
  **③b churn 은 본 버그 수정이 전제**다(수정 전엔 5xx 로 불합격). 수정 후 유효.
- `load-tests/common/helpers.js` — `getEntryToken` 에 `tags:{type:'entry'}`, `cancelReservation` 추가.
- `load-tests/scripts/Run-Scenario-Container.ps1` — `-AdmissionMax` 파라미터 추가(기본 2000, L4 는 100).

커밋 전략 제안: **러너/헬퍼/L4 하드닝**(테스트 인프라)과 **앱 버그 수정**(도메인/스키마)을 분리 커밋.
앱 수정이 머지돼야 L4 가 그린이 되므로, 앱 수정 먼저 → L4 재측정 그린 확인 → 함께 정리.

---

## 6. 추천

**A-2 (활성 한정 부분 유니크)**. 이 프로젝트의 정체성이 "동시성·정합성 다층 증명"이라, A-1 처럼
방어 층을 버리기보다 **층을 유지하며 버그만 제거**하는 편이 서사·품질 모두 우위. 발견 경위(부하
테스트 churn → 재예매 불가 → 생성 컬럼 부분 유니크로 해결)가 C4(AI 활용)·C7(나만의 관점) 소재.
단순·최소가 우선이면 A-1 이 차선.

## 7. 체크리스트 등록 (착수 시)
- `KTX_Ticketing_Task_Checklist.md` 기술 부채 백로그에 **B-2** 로 등록(독립 브랜치 + 계획 승인).
- 연관: T3-9(만료 복구)·T3-11(정합성 통합 테스트)·T4-6(L4).
