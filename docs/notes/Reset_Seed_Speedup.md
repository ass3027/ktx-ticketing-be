> 상위 문서: `KTX_Ticketing_Performance_Test.md`
> 출처: 2026-06-17 세션 — `load-tests/scripts/Run-L1.ps1` 회차당 소요 시간 단축 작업

# Reset-Seed 속도 개선 — 28.8s → 13.8s (52% 단축)

## 0. 배경

`Run-L1.ps1` 의 각 회차는 `Reset-Seed.ps1` 로 시작한다.
DB TRUNCATE → Redis FLUSHDB → 앱 재기동 → health UP 대기 순서로 동작하며,
재기동 자체를 유지한 채 회당 시간을 줄이는 것이 목표였다.

베이스라인 측정(워밍업이 정상 동작하던 시점):

| 단계 | 시각 차 | 누적 |
|---|---:|---:|
| Spring 콜드 스타트 | 8.3s | 8.3s |
| DataInitializer (50,000 seat_inventory + 10,000 user) | 4.2s | 12.5s |
| **AvailPoolWarmup (50,000 단건 SADD + HGET)** | **12.0s** | 24.5s |
| 폴링·컨테이너 오버헤드 | ~3~5s | **~28.8s** |

가장 큰 단일 비용은 워밍업의 좌석별 RTT, 그 다음이 Spring 콜드 스타트였다.

## 1. 적용한 최적화 3종

### 1.1 health 폴링 간격 단축 (3s → 1s)

`load-tests/scripts/Reset-Seed.ps1` 의 `Start-Sleep -Seconds 3` 을 `1` 로.
앱이 UP 된 직후 발견 지연을 평균 1.5s → 0.5s 로 줄인다.
무위험·1줄 변경.

### 1.2 워밍업 missing 루프 pipeline batch (`12.0s → 0.5s`)

#### 병목 원인

`AvailPoolWarmup` 은 `ReconciliationService.reconcileSchedule()` 을 호출한다.
FLUSHDB 직후라 풀이 비어 있어, 50,000 좌석 전부가 *missing* 분기로 들어간다.
missing 루프는 좌석마다:

```
preemption.preemptedAtMillis(scheduleId, seatId)   // HGET 1 RTT
preemption.returnSeat(scheduleId, seatId)          // SADD 1 RTT
```

좌석당 2 RTT × 50,000 = **100,000 RTT ≈ 12초** (로컬 docker Redis 기준).

#### 해결 — 벌크 helper 도입

`SeatPreemption` 인터페이스에 두 메서드를 default 로 추가하고
`RedisSetPreemption` 에서 실제 구현, `ReconciliationService` missing 루프만 교체.

| 호출 | 변경 전 | 변경 후 |
|---|---|---|
| 선점 시각 조회 | `preemptedAtMillis` × N 회 (HGET) | `preemptedAtMillisAll` 1 회 (HGETALL) |
| 가용 풀 반환 | `returnSeat` × N 회 (SADD 단건) | `returnSeats` 1 회 (SADD 가변인자) |

`preemptedAtMillisAll` 은 default 로 N회 HGET 흉내 내봐야 RTT 절감 효과가 없어 의미가 없다 →
`UnsupportedOperationException` 으로 구현체 책임을 명시.
`returnSeats` 만 단건 호출 fallback 을 두어 mock 호환성을 유지.

stale 루프는 그대로(워밍업 경로에서 stale = 0 건이라 효과가 없고, 손대면 테스트 verify 순서까지 깨짐).

#### 결정 로직 동치성

`now - preemptedAt > graceMillis` grace 비교는 그대로다.
동일 좌석이 동일 조건으로 풀에 되돌아가므로 오버셀 방지 계약(`docs/KTX_Ticketing_Reconcile_Design.md` §7)은 보존된다.

### 1.3 JDK 25 AOT cache 도입 (Spring 콜드 스타트 `8.3s → 5.7s`)

#### 무엇

JEP 483 — AOT cache 는 AppCDS 의 상위 집합으로, 클래스 메타데이터뿐 아니라
**link / initialize 결과까지 사전 dump** 해서 부팅 시 mmap 으로 복원한다.
런타임 JIT / 처리량에는 영향이 없고 부팅 시간만 단축된다.

#### 적용 형태 (옵션 C — 컨테이너 첫 실행 시 dump)

build 시점 dump 는 host network·datasource 회피 등 빌드 재현성을 해친다.
대신 컨테이너 entrypoint 가 dump 파일 없으면 1회 dump → 본 실행.

```
신규 docker-entrypoint.sh:
  if [ ! -f /app/app.aot ]; then
    java -XX:AOTCacheOutput=/app/app.aot \
         -Dspring.context.exit=onRefresh \
         -jar /app/app.jar
  fi
  exec java -XX:AOTCache=/app/app.aot -jar /app/app.jar
```

- `spring.context.exit=onRefresh` (Spring Boot 3.2+): 컨텍스트 refresh 완료 직후 종료.
  `ApplicationRunner` (DataInitializer, AvailPoolWarmup) 는 refresh 이후 단계라 dump 중 실행되지 않음 →
  **시드/워밍업 부수효과 없음.**
- mysql/redis 가 `depends_on: condition: service_healthy` 로 보장되므로 dump 가 EntityManager 초기화에 성공.
- 캐시 파일은 컨테이너 layer 안 (`/app/app.aot`, 약 139MB) 에 영구. `--build` 로 재빌드할 때만 재생성.

## 2. 측정 결과 (실측)

| 측정 항목 | 베이스라인 | + 폴링 단축 + pipeline | + AOT cache |
|---|---:|---:|---:|
| Spring 콜드 스타트 (`Started in ...`) | 8.3s | 7.1s | **5.7s** |
| DataInitializer | 4.2s | 3.8s | 4.0s |
| AvailPoolWarmup | **12.0s** | 0.5s | **0.4s** |
| polling/overhead | ~3~5s | ~3~5s | ~3~5s |
| **Reset-Seed 총 wall-clock** | **28.8s** | **16.4s** | **13.8s** |
| Run-L1.ps1 3회차 합계 절감 | — | −37s | **−45s** |

### 첫 컨테이너 부팅 (1회성 비용)

AOT cache dump 단계가 추가돼 첫 부팅은 약 10초 더 걸린다.
회차 반복에선 그 비용이 한 번만 발생하고 이후엔 모두 캐시 hit.

### 정합성 회귀 확인

- `SCARD avail:1` … `SCARD avail:50` = 모두 1,000 (좌석당 1000개 정상 적재)
- 단위 테스트: `RedisSetPreemptionTest`, `ReconciliationServiceTest` 전부 그린
- 통합 테스트: `ReconciliationIntegrationTest` 그린

## 3. 변경 파일

### 코드
- `src/main/java/com/ktx/ticketing/booking/SeatPreemption.java`
  — `preemptedAtMillisAll`, `returnSeats` default 메서드 2개 추가
- `src/main/java/com/ktx/ticketing/booking/RedisSetPreemption.java`
  — HGETALL 1회 / 가변인자 SADD 1회 구현
- `src/main/java/com/ktx/ticketing/booking/reconcile/ReconciliationService.java`
  — missing 루프를 벌크 호출 2회로 압축

### 인프라
- `Dockerfile` — entrypoint 를 `docker-entrypoint.sh` 로 교체
- `docker-entrypoint.sh` — 신규. 첫 실행 시 AOT cache dump, 이후 `-XX:AOTCache=` 로 부팅

### 스크립트
- `load-tests/scripts/Reset-Seed.ps1` — 폴링 `Start-Sleep -Seconds 3 → 1`

### 테스트
- `src/test/java/com/ktx/ticketing/booking/RedisSetPreemptionTest.java`
  — 신규 unit test 4개 (`returnSeats` 2건, `preemptedAtMillisAll` 2건) + setUp `lenient()` 처리
- `src/test/java/com/ktx/ticketing/booking/reconcile/ReconciliationServiceTest.java`
  — stub/verify 를 벌크 메서드 기준으로 교체

## 4. 검증 절차 (재현 가이드)

```bash
# 1) 단위 + 통합 테스트
./gradlew test --tests "*RedisSetPreemptionTest" \
              --tests "*ReconciliationServiceTest" \
              --tests "*ReconciliationIntegrationTest"

# 2) 컨테이너 재빌드 (entrypoint + AOT cache dump 포함)
docker compose up -d --build app

# 3) 첫 부팅 (dump 1회) 완료 대기
#    health UP 까지 약 24초 (이후 회차는 13~14초)

# 4) Reset-Seed 실측
./load-tests/scripts/Reset-Seed.ps1
docker compose logs app | Select-String "Seed data ready|부팅 워밍업|AOT"

# 기대값:
#   - AOT cache 144MB 로그 (첫 부팅에만)
#   - Started KtxTicketingApplication in ~5.7 seconds
#   - 부팅 워밍업: avail 풀 적재 50000건  (워밍업 로그 시각 - DataInitializer 완료 시각 < 1s)
#   - Reset-Seed 총 wall-clock ≈ 13~14초

# 5) L1 회귀
./load-tests/scripts/Run-L1.ps1 -Iterations 1
#   - Invoke-PostRunCheck: oversell 0, duplicate 0
```

## 5. 적용하지 않은 후보와 이유

| 후보 | 이유 |
|---|---|
| `-XX:TieredStopAtLevel=1` | JIT 최상위 컴파일을 끄면 부하 테스트 정상 성능 측정이 왜곡됨 |
| GraalVM native image | JIT 성능·툴 호환성 차이로 부하 테스트 신뢰도 저하 |
| `spring.main.lazy-initialization=true` | ApplicationRunner 가 어차피 부팅 후 즉시 도는 빈에 의존 → 효과 작을 가능성. 측정 후 효과 확인되면 별도 적용 가능 |
| 앱 재기동 자체 제거 (admin endpoint 로 시드/워밍업 트리거) | 효과는 가장 크지만(콜드 스타트 0초) 변경 폭이 커 별 PR 로 분리해야 함 |
| Redis pipeline 워밍업의 stale 루프까지 벌크화 | 워밍업 경로에서 stale = 0 건이라 효과 없음. 손대면 reconcile 테스트 verify 순서까지 깨짐 |

## 6. 향후 작업 후보

- Spring `lazy-initialization=true` 의 실측 효과 측정. 1~3s 추가 단축 가능성.
- 컨테이너 재기동 대신 admin endpoint 로 시드/워밍업을 트리거하는 옵션 — Reset-Seed 를 5~7초대로 가능성 큼.
- AOT cache 파일 크기(139MB) 가 build context 에 영향을 주는지 점검. layer 캐시 효율이 떨어지면 별 volume 으로 분리 고려.
