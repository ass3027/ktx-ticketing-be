# 부하 테스트 실행 가이드

## 전제조건

- k6 로컬 설치: https://grafana.com/docs/k6/latest/set-up/install-k6/
- Docker Compose 앱 기동 중: `docker compose up --build`
- MySQL, Redis 접근 가능

## 실행 전 준비

```bash
# DB/Redis 초기화 후 앱 재기동 → DataInitializer 자동 재시드
make reset-seed
docker compose restart app
```

## 시나리오 실행

```bash
make run-L1    # 정합성 검증 (가장 중요, 항상 먼저 실행)
make run-L2    # 정상 혼합 부하 (p95/TPS 기준선)
make run-L2b   # AUTO 배정 처리량
make run-L3    # 조회 폭주 (5,000 VUser)
make run-L4    # 입장 제어 초과 부하
make run-L5    # 임계점 탐색 (계단식 증가)
make run-L6    # 지속 부하 30분 (누수 확인)
```

k6 내장 대시보드: http://localhost:5665

### L1 실행 절차 (T4-3)

L1 은 *좌석 선점 정합성* 검증이라 입장 제어를 우회해야 1,000 VU 가 좌석 경쟁까지
도달한다. 운영 잠정값(`max-active=100`)으로 두면 ~900 이 429 에서 차단된다.

#### Windows / PowerShell 자동화 (권장)

PowerShell 스크립트(`load-tests/scripts/`)가 reset+restart+health-wait+k6+정합성 검증을
한 번에 처리한다. `make`/`bash` 불필요, mysql/redis-cli 도 컨테이너 경유라 호스트 설치 의존 없음.

1. **본 측정 (기본 3회)** — PowerShell 7 터미널에서:
   ```powershell
   pwsh load-tests/scripts/Run-Scenario-Container.ps1 `
     -Scenario load-tests/scenarios/L1_single_seat_race.js -PostRunCheck
   # 회수 변경: 위 명령에 -Iterations 5
   ```
   k6 를 app 과 같은 docker 네트워크에서 실행(`docker-compose.k6.yml`, BASE_URL=http://app:8080)해
   Windows 포트 프록시(NAT)/호스트 TIME_WAIT 압박을 우회한다(refused=0, §`docs/notes/K6_Port_Exhaustion_Troubleshooting.md`).
   입장 제어(K) 우회는 러너가 `BOOKING_ADMISSION_MAX_ACTIVE=2000` 을 매 회차 자동 주입하므로
   `Set-AdmissionOverride.ps1` 은 불필요(수동/레거시 경로에서만 사용).
   각 회 raw 로그(`load-tests/results/L1_container_run_$i.txt`) + 정합성 결과
   (`L1_container_run_$i.check.txt`) 가 누적된다(gitignore).
2. **결과 표 기입**: `docs/results/P4_Result.md` T4-3 섹션의 회차별 행에 reserve_ok / oversell /
   p50·p95·p99 / DB·Redis 단언 결과 기록.

#### bash/make 사용자 (Linux/macOS/Git Bash)

```bash
for i in 1 2 3; do
  make reset-seed && docker compose restart app
  sleep 10
  make run-L1 | tee load-tests/results/L1_run_$i.txt
  mysql -h127.0.0.1 -uktx -pktx1234 ktx_ticketing < load-tests/verify/post_run_check.sql
  redis-cli SCARD avail:1
done
```

합격: 모든 회차에서 oversell=0 / reserve_ok=1 / DB HELD=1 / DB AVAILABLE=999 / SCARD=999.

결과 표는 `docs/results/P4_Result.md` T4-3 섹션에 누적.

### docker-compose 오버라이드 (K 우회용)

L1 측정 시 입장 제어(K) 상한을 우회하려면 `docker-compose.override.yml` 이 필요하다(gitignore 대상).
`Set-AdmissionOverride.ps1` 이 생성/삭제 + app recreate + health 대기를 토글로 처리한다:

```powershell
./load-tests/scripts/Set-AdmissionOverride.ps1 -On            # 우회 활성 (max-active=2000)
./load-tests/scripts/Set-AdmissionOverride.ps1 -On -MaxActive 5000
./load-tests/scripts/Set-AdmissionOverride.ps1 -Off           # 운영값 복원 (override 삭제)
```

스크립트 없이 수동으로 할 경우 — `docker-compose.override.yml` 을 다음 내용으로 두면
`docker compose up` 시 자동 머지된다(측정 종료 후 파일 삭제 또는 값 복원):

```yaml
services:
  app:
    environment:
      BOOKING_ADMISSION_MAX_ACTIVE: 2000
```

## 실험 Before/After

| 실험 | Before | After |
|------|--------|-------|
| E1 선점 제어 | `make run-E1-before` | `make run-E1-after` |
| E2 입장 제어 | `make run-E2-before` | `make run-E2-after` |
| E3 조회 캐시 | `make run-E3-before` | `make run-E3-after` |

E1/E3 Before 실행 전: 앱에 토글 환경변수 필요 (T4-9 구현 후 확인)

## 합격 기준

| 시나리오 | 지표 | 기준 |
|----------|------|------|
| L1 | reserve_ok | == 1 (k6 threshold) |
| L1 | oversell | == 0 (DB `post_run_check.sql` 로 판정) |
| L2 | 예매 p95 | ≤ 500ms |
| L2 | 예매 p99 | ≤ 1,000ms |
| L2 | 조회 p95 | ≤ 200ms |
| L2 | 5xx 율 | < 1% |
| L2 | TPS | ≥ 200 |
| L3 | 조회 p95 | ≤ 200ms |
| L4 | 5xx 율 | < 1% (429/503 제외) |

## 부하 후 정합성 확인

```bash
mysql -h127.0.0.1 -uktx -pktx1234 ktx_ticketing
```

`load-tests/verify/post_run_check.sql` 의 쿼리를 실행해 DB 정합성을 수동 확인한다.

## 환경변수

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `BASE_URL` | `http://localhost:8080` | 앱 주소 |
| `SCHEDULE_ID` | `1` | L1 대상 스케줄 ID |
| `SEAT_INVENTORY_ID` | `1` | L1 인기 좌석 재고 ID |

```bash
# 예: 특정 좌석 ID 지정
SCHEDULE_ID=5 SEAT_INVENTORY_ID=42 make run-L1
```
