# P4 성능 측정 결과 (M4 진행 중)

> 출처: `KTX_Ticketing_Task_Checklist.md`, `KTX_Ticketing_Performance_Test.md`
> DoD(M4): SLO 충족/미달 사유 + Before/After 그래프 + 임계점 수치
> 사용법: P4 태스크가 완료될 때마다 본 문서에 섹션을 누적한다. 측정 수치는 환경 의존이라
> *실측자가 직접* 기입(시나리오/문서 정비는 코드 변경, 측정 실행은 사용자 책임).

---

## 진행 현황

| 태스크 | 상태 | 비고 |
|--------|------|------|
| T4-1 부하 환경 구축(k6) | ✅ | `load-tests/` 골격 + L1~L6/E1~E3 스크립트 + Makefile + reset.sh + post_run_check.sql |
| T4-2 서버 모니터링 | ✅ | Actuator + Micrometer + Prometheus + Grafana(JVM 대시보드) + Lettuce Redis 명령 지연 계측 |
| T4-3 L1 직접선택 단일좌석 경쟁(정합성) | 🔧 정비 완료, 측정 대기 | L1 시나리오 K 우회·반복 reset 안내·SCARD 검증 보강 |
| T4-4 L2/L2b 정상·자동배정 처리량 | ⏳ | |
| T4-5 L3 조회 폭주 | ⏳ | |
| T4-6 L4 입장 초과 | ⏳ | |
| T4-7 L5 임계점 탐색(K 확정) | ⏳ | `booking.admission.max-active` 잠정값 100 → L5 결과로 확정 |
| T4-8 L6 지속 부하(soak) | ⏳ | |
| T4-9 E1·E2·E3 Before/After | ⏳ | E1-before 토글(`booking.preemption.enabled=false`) 구현 필요 |
| T4-10 E5 가상 스레드 | ⏳ | |
| T4-11 E6 분산 락 라이브러리 비교 | ⏳ | `DistributedLock` 추상화는 완료 |
| T4-12 E7 선점 백엔드(Redis vs Memcached) | ⏳ | `SeatPreemption` 추상화는 완료 |
| T4-13 만료 sweep 벌크 최적화 | ⏳ | 트리거 조건: T4-8 에서 sweep 병목 측정 시 |

---

## T4-1 — 부하 환경 구축 (완료, 2026-06-11 ~ 2026-06-12 5814982/24d2e0c)

### 산출물
| 위치 | 내용 |
|------|------|
| `load-tests/scenarios/L1~L6_*.js` | k6 시나리오 6종 — VU·iter·threshold 명시 |
| `load-tests/experiments/E1~E3_{before,after}_*.js` | Before/After 토글 실험 6종 |
| `load-tests/common/{config,helpers}.js` | 공용 환경변수·API 헬퍼(`getEntryToken`/`bookSeat`/`bookAuto`/`confirmReservation`/`listSchedules`) |
| `load-tests/seed/reset.{sh,sql}` | DB TRUNCATE + Redis FLUSHDB. 앱 재기동 시 DataInitializer 재시드 |
| `load-tests/verify/post_run_check.sql` | 부하 후 정합성 6개 쿼리(L1 합격 단언 ①·④, Redis SCARD 안내 포함) |
| `Makefile` | `make run-L1`...`make run-E3-after` 단축 + `make reset-seed` |
| `load-tests/README.md` | 실행 절차, K 우회 docker-compose override, 합격 기준 표 |

### 외부 의존
- k6 로컬 설치 (`grafana.com/docs/k6`)
- Docker Compose 앱 기동 (`docker compose up --build`)
- mysql-client·redis-cli (정합성 검증용)

---

## T4-2 — 서버 모니터링 (완료, 2026-06-12 ea0fc75/89aa96e)

### 산출물
| 위치 | 내용 |
|------|------|
| `docker-compose.monitoring.yml` | Prometheus + Grafana 컨테이너 정의 |
| `monitoring/prometheus.yml` | scrape 설정 (앱 actuator/prometheus 엔드포인트) |
| `monitoring/grafana/provisioning/*` | datasource·dashboard 프로비저닝 |
| `monitoring/grafana/dashboards/jvm.json` | JVM 대시보드(CPU/메모리/GC/스레드) |
| `application.yml` (management.*) | Actuator 노출 + Micrometer Prometheus 등록 |
| Lettuce CommandLatencyCollector | Redis 명령별 지연 히스토그램 자동 노출 |

### 접속
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin)
- 앱 메트릭: http://localhost:8080/actuator/prometheus

### 측정 가능 지표
- JVM: heap, GC pause, 스레드 풀(현재 플랫폼/가상 토글 시 비교용)
- HTTP: `http_server_requests_seconds` (Spring Boot 자동)
- Redis: `lettuce_command_completion_seconds` (명령별 p50/p95/p99 — 선점 SREM/SPOP·SCARD 지연 분석)
- DB 커넥션: Hikari 풀(`hikaricp_connections_*`)

---

## T4-3 — L1 직접 선택 단일 좌석 동시 경쟁 (정비 완료, 측정 대기)

### 목적
1,000 VUser 가 동일 좌석 1개에 동시 SEAT 예매 → **HELD = 1, oversell = 0** (S4) 을 HTTP 전 구간에서 재현.
M2 의 `ConcurrencyPocTest`(서비스 계층) 검증을 HTTP + Redis + DB 풀스택으로 확장한다.

### 정비 내역 (코드/문서 변경 — 측정과는 별개)
| 파일 | 변경 |
|------|------|
| `load-tests/scenarios/L1_single_seat_race.js` | 헤더에 K 우회·반복 시 reset 필요·검증 위치 명시 |
| `load-tests/verify/post_run_check.sql` | L1 합격 단언(①·④·SCARD) 머리말 추가 |
| `load-tests/README.md` | L1 실행 절차(3회 반복) + docker-compose override 패턴 |
| `.gitignore` | `docker-compose.override.yml` + `load-tests/results/` 제외 |

### 실행 (사용자가 직접)

Windows/PowerShell 자동화 스크립트 사용. `make`/`bash`/호스트 mysql·redis-cli 불필요
(컨테이너 경유). bash 사용자는 `load-tests/README.md` 의 `for` 루프 참조.

1. **K 우회 토글**: `docker-compose.override.yml` 은 이미 신설됨
   (`BOOKING_ADMISSION_MAX_ACTIVE: 2000`, gitignore).
2. **재기동**: `docker compose up -d --force-recreate app`
3. **3회 반복 측정 + 정합성 검증 (한 줄)** — PowerShell 7 터미널에서:
   ```powershell
   ./load-tests/scripts/Run-L1.ps1
   ```
   다른 셸에서는 `pwsh load-tests/scripts/Run-L1.ps1`.
   매 회 reset+restart+health 대기→k6→DB/Redis 정합성을 자동 수행.
   raw 로그는 `load-tests/results/L1_run_$i.{txt,check.txt}` (gitignore).
4. **K 복원**: `docker-compose.override.yml` 삭제 → `docker compose up -d --force-recreate app`.

### 합격 기준 (S4 — 정합성)
- k6 threshold: `oversell == 0` AND `reserve_ok == 1`
- DB: `reservation` 의 `seat_inventory_id=1` 행 = HELD 1건, 다른 상태 0건
- DB: `seat_inventory.status=AVAILABLE` AND `schedule_id=1` = 999건
- Redis: `SCARD avail:1` = 999 (DB 와 정확히 일치 = 드리프트 0)

### 측정 결과 (사용자 기입)

| 회차 | 일시 | reserve_ok | oversell | 예매 p50 | p95 | p99 | 예매 5xx | DB HELD(좌석 1) | DB AVAILABLE(스케줄 1) | Redis SCARD avail:1 | 합격? |
|------|------|------------|----------|----------|-----|-----|---------|------------------|------------------------|---------------------|-------|
| 1    |      |            |          |          |     |     |         |                  |                        |                     |       |
| 2    |      |            |          |          |     |     |         |                  |                        |                     |       |
| 3    |      |            |          |          |     |     |         |                  |                        |                     |       |

> 이후 E1(선점/락) Before/After 의 **After 케이스 = 본 L1 결과**가 됨(T4-9 입력). raw 로그(`load-tests/results/L1_run_*.txt`) 는 gitignore.

### 환경 메타 (사용자 기입)

| 항목 | 값 |
|------|-----|
| CPU | |
| RAM | |
| OS | |
| Docker Desktop ver. | |
| k6 ver. | |
| MySQL ver. | 8.0 (docker-compose 고정) |
| Redis ver. | 7-alpine (docker-compose 고정) |
| 앱 빌드 commit | |

### 관찰·해석 (사용자 기입)

> 측정 후 한 단락으로 정리. 합격이면 "S4 달성, M2 PoC 의 HTTP 풀스택 재현 완료" 정도, 불합격이면
> 어느 단언이 깨졌는지 + 어떤 경로(선점 race / 락 시점 / 부수효과 누락) 인지 가설.
