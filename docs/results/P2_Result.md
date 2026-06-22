# P2 핵심 PoC 결과 (M2 달성)

> 작성일: 2026-06-01
> DoD: 동시성 PoC 초과 판매 0건 확인 ✅

---

## 검증 결과 (T2-5)

| 실험 | 방식 | 조건 | 성공 | 실패 | oversell |
|------|------|------|------|------|----------|
| **E1(A)** | Redis 선점(SREM) + 낙관락 | 1,000 동시요청 / 단일 좌석 | 1 | 999 | **0** ✅ |
| **E1(B)** | Redisson 분산락 | 1,000 동시요청 / 단일 좌석 | 1 | 999 | **0** ✅ |

---

## 구현 구조

### 2단계 선점 구조 (Plan A)

```
[1단계] Redis Set  avail:{schedule_id}
  SEAT 모드: SREM avail {invId}  → 반환 1 = 선점 승자
  AUTO 모드: SPOP avail          → 뽑힌 invId = 선점 승자
      ↓ (승자만)
[2단계] DB 상태전이  AVAILABLE → HELD
  + @Version 낙관적 락 (최종 방어선)
```

### 분산락 구조 (E1 비교용)

```
Redisson tryLock(5s wait, 10s lease)
    ↓ (락 획득 성공)
BookingTransactionHelper.holdSeat()  ← @Transactional (외부 빈 호출)
    → DB 조회 → 상태 확인 → HELD 전이 → Reservation 저장
    ↓ (트랜잭션 커밋 후)
lock.unlock()
```

---

## 구현 파일

| 파일 | 역할 | 태스크 |
|------|------|--------|
| `booking/SeatPreemptionService.java` | Redis SREM/SPOP/SADD 원자 선점 | T2-1, T2-2 |
| `booking/BookingService.java` | Redis 선점 → DB HELD 전이 | T2-3 |
| `booking/LockBookingService.java` | Redisson 분산락 → DB HELD 전이 (비교용) | T2-4 |
| `booking/BookingTransactionHelper.java` | 락 외부에서 @Transactional 보장 | T2-4 |
| `domain/SeatInventoryRepository.java` | JPA 레포 + 상태별 조회/카운트 | 공통 |
| `domain/ReservationRepository.java` | JPA 레포 | 공통 |
| `domain/UserRepository.java` | JPA 레포 | 공통 |
| `domain/ScheduleRepository.java` | JPA 레포 | 공통 |
| `test/.../ConcurrencyPocTest.java` | 1,000 동시 + E1 A/B 검증 | T2-5 |
| `test/resources/application-local.yml` | 테스트에서 실제 MySQL/Redis 연결 | 인프라 |

---

## 설계 결정 및 트레이드오프

### Redis SREM이 1차 원자 게이트인 이유
- `SREM`은 Redis 단일 스레드 모델에서 원자적으로 실행 → 1,000 요청 중 정확히 1개만 반환값 1
- DB 락 없이도 대부분의 경쟁을 Redis 레벨에서 차단 → DB 부하 최소화
- 단점: Redis 장애 시 avail Set 불일치 가능 → reconcile 잡(T3-10)으로 보정 예정

### @Version 낙관락이 최종 방어선인 이유
- Redis 선점을 통과한 1개 요청도 DB 쓰기 시 충돌 가능 (네트워크 재시도 등 예외 상황)
- `@Version` increment 실패 시 `OptimisticLockException` → 롤백으로 이중 보호
- P4 E1 실험에서 낙관락 없는 경우(DB 락만) vs Redis+낙관락 성능 차이 측정 예정

### BookingTransactionHelper 분리 이유
- `LockBookingService.doBookSeat()`를 같은 클래스 내에서 호출하면 Spring AOP 프록시가 적용되지 않아 `@Transactional` 무시됨
- 트랜잭션 없이 실행 시: 여러 스레드가 DB에서 동시에 AVAILABLE 확인 → 중복 예약 발생 (duplicate key 오류 확인)
- 해결: 별도 `@Service` 빈으로 분리 → 외부 호출 → 프록시 적용 → 트랜잭션 보장

---

## 발견한 이슈 및 해결

| 이슈 | 원인 | 해결 |
|------|------|------|
| H2 `LAST_INSERT_ID` not found | `@SpringBootTest`에서 H2 자동설정이 MySQL을 override | `src/test/resources/application-local.yml` 추가로 MySQL/Redis 명시 |
| 분산락 테스트에서 duplicate key 오류 | 동일 클래스 내 `@Transactional` 호출 → AOP 프록시 미적용 → 트랜잭션 없이 실행 | `BookingTransactionHelper` 별도 빈 분리 |

---

## 커밋 이력 (P2)

| 해시 | 내용 |
|------|------|
| `28254c0` | feat: JPA Repository 추가 (SeatInventory, Reservation, User, Schedule) |
| `e122dcb` | feat(T2-1,T2-2): SeatPreemptionService — Redis SREM/SPOP 선점 로직 + 단위 테스트 |
| `8a20034` | feat(T2-3): BookingService — Redis 선점 후 DB HELD 상태전이 + 단위 테스트 |
| `9671bec` | feat(T2-4): LockBookingService — Redisson 분산락 비교용 예매 서비스 + 단위 테스트 |
| `044f2fe` | test(T2-5): ConcurrencyPocTest — 1,000 동시요청 oversell=0 검증 (E1 비교 포함) |
| `e2e1052` | fix(T2-4,T2-5): LockBookingService 트랜잭션 경계 수정 + 테스트 설정 보완 |
| `796efce` | docs: P2 체크리스트 완료 처리 (M2 ✅) |

---

## 다음 단계 (P3)

- T3-1: 운행 리스트 조회 API
- T3-2: 매진/잔여석 표시 (Redis 카운터, 약한 일관성)
- T3-3~T3-5: 입장 제어 + EntryToken
- T3-6~T3-8: 예매/결제 API (P2 선점 통합)
- T3-9: HELD TTL 만료 스케줄러
- T3-10: Redis-DB reconcile 잡
- T3-11: 통합 테스트 (정합성 자동화)
