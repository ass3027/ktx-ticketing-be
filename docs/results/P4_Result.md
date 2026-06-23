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
| T4-3 L1 직접선택 단일좌석 경쟁(정합성) | ✅ | **oversell=0·중복=0 3회 일관 달성** (호스트 JVM, refused≈0). 1 win / 999 정상 패배 |
| T4-4 L2/L2b 정상·자동배정 처리량 | ✅ | L2: 예매 p95≤500ms·TPS 833~863·5xx 0.03% 합격 / **list p95~0.9s SLO(200ms) 미달**(→L3·E3). L2b: AUTO 1000석 정확 매진·oversell 0 |
| T4-5 L3 조회 폭주 | ⏳ | |
| T4-6 L4 입장 초과 | 🟡 | **B-2 버그 수정 후 smoke(1회) 그린**: server_errors 0(수정 전 100)·reject 85.8%·k6Exit 0. 본 측정(3회) 미실시(실측자) |
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

## T4-3 — L1 직접 선택 단일 좌석 동시 경쟁 (완료, 2026-06-17)

### 목적
1,000 VUser 가 동일 좌석 1개에 동시 SEAT 예매 → **HELD = 1, oversell = 0** (S4) 을 HTTP 전 구간에서 재현.
M2 의 `ConcurrencyPocTest`(서비스 계층) 검증을 HTTP + Redis + DB 풀스택으로 확장한다.

### 측정 결과 (호스트 JVM, 연속 5회)

| 회차 | reserve_ok | oversell | http_reqs | 예매 p95 | refused | DB HELD(좌석1) | DB AVAILABLE(스케줄1) | Redis SCARD | 합격? |
|------|-----------|----------|-----------|---------|---------|---------------|----------------------|-------------|-------|
| 1 | 1 | **0** | 2000 | 1.03s | **0** | 1 | 999 | 999 | ✅ |
| 2 | 1 | **0** | 2000 | 1.46s | **0** | 1 | 999 | 999 | ✅ |
| 3 | 1 | **0** | 2000 | 1.04s | **0** | 1 | 999 | 999 | ✅ |
| 4 | 1 | **0** | 1938 | 1.05s | 62 | 1 | 999 | 999 | ✅ |
| 5 | 1 | **0** | 1926 | 1.12s | 74 | 1 | 999 | 999 | ✅ |

- **5회 전부 oversell=0 / 중복=0 / win 정확히 1건** → **S4 달성. M2 PoC 의 HTTP 풀스택 재현 완료.**
- `checks` = `1 ✓ / 999 ✗` = 1명만 201(win), 999명 409 SeatTaken(정상 경쟁 패배). 의도된 결과.
- **권장 측정 구간 = 냉간 시작 1~3회차**(refused=0 → 진짜 1,000 동시 보장). 아래 "refused 추세" 참조.
- raw: `load-tests/results/L1_host_run_*.{txt,check.txt}` + `L1_summary.json` (gitignore).

> 직전 3회 측정(별도 실행)에서는 회차 3에 refused 381 스파이크가 있었으나, 본 5회 재측정에서 같은
> 위치는 0 — 고정적 결함이 아닌 변동성 노이즈(아래 추세 분석)로 확인됨. 정합성은 양쪽 모두 무영향.

### 지표 해석 (오독 방지)

- **`http_req_failed` = 0% 가 5xx=0 단언이다.** `setResponseCallback(expectedStatuses(2xx,409,410,429))`
  로 999명의 정상 경쟁 패배(409 SeatTaken)를 실패 집계에서 제외 → 진짜 5xx/연결 실패만 집계.
  threshold `http_req_failed: rate<0.01` 가 패배자의 5xx 누수를 자동 차단(L2/L2b 와 동일 집계).
- **p95 ≈ 1.4s 는 booking SLO(p95 ≤ 500ms) 를 초과하지만 L1 에선 정상.** L1 은 *단일 좌석에
  1,000 요청이 동시 직격*하는 최악 경쟁 시나리오로, 목적은 **정합성**이지 처리량이 아니다.
  분산 락(Redisson) 임계 구간을 1,000 요청이 직렬로 통과하므로 꼬리 지연이 길다.
  일반 처리량 SLO 는 L2(T4-4)에서 측정한다.

### 부수 발견 — 측정 환경의 connection refused 와 NAT 격리 (이번 세션)

초기 측정(Docker 컨테이너 앱)에서 1,000 VU 의 **60~78%(회차당 609~777건)가 `/api/entry`
단계에서 `connectex: actively refused`(RST)** 로 앱에 도달조차 못 했다. 정합성은 통과했으나
실제 경쟁이 ~370 으로 줄어 L1 의 "1,000 동시" 전제가 깨졌다. 원인을 단계적으로 격리:

| 가설 | 실험 | refused/1000 | 결론 |
|------|------|-------------:|------|
| IPv6 해석 미스 | `config.js` BASE_URL `localhost`→`127.0.0.1` | 631 (잔존) | 부분 원인(주원인 아님) |
| Tomcat accept backlog | `server.tomcat.accept-count` 100→1000 | 710 (무변화) | **아님** |
| **Docker Desktop NAT 포화** | 앱을 **호스트 JVM**(NAT 우회)으로 실행 | **95 → 0** | **확정** |
| host networking 으로 NAT 제거 | `network_mode: host` | — (Windows 접근 불가) | **부적합** |

- **결론: refused 의 주원인은 Docker Desktop(Windows/WSL2)의 포트 프록시(NAT) 가 1,000 동시
  SYN burst 를 못 받아 RST 를 낸 것.** 앱·Tomcat 설정과 무관. 동일 1초에 거부가 몰림.
- `network_mode: host` 는 Windows Docker Desktop 에서 포트 프록시를 제거해 오히려 호스트
  `127.0.0.1:8080` 접근이 끊겨(컨테이너는 격리된 docker-desktop WSL VM 안에만 존재) **부적합**.
- → **L1 본측정은 앱을 호스트 JVM 으로 실행**해 NAT 를 우회하는 방식으로 고정. 위 표가 그 결과.

#### 호스트 JVM 의 refused 잔여 — TIME_WAIT 누적 (연속 5회 추세)

NAT 를 제거해도 호스트 JVM 에서 refused 가 완전 0 은 아니었다. 연속 5회 추세가 원인을 드러낸다:

| 회차 | 1 | 2 | 3 | 4 | 5 |
|------|---|---|---|---|---|
| refused | 0 | 0 | 0 | 62 | 74 |

- **초반 3회 0 → 후반 증가** 패턴 = **TIME_WAIT 누적**. 회차당 ~2,000 연결이 TIME_WAIT(Windows
  기본 240s)로 남아, 회차가 쌓일수록 ephemeral 포트/소켓 압박이 커져 다음 burst 의 일부가 RST.
  (동적 포트 49152~65535 = 16,384개로 단일 회차엔 충분하나, 연속 실행 시 잔류분 누적.)
- 따라서 **변동성 노이즈가 아니라 측정 절차상 효과** — 직전 3회 측정의 회차3 381 스파이크도
  같은 누적·OS 스케줄링 변동의 발현(위치는 가변).
- **권장: 냉간 시작 후 1~3회차를 대표값으로 채택**(refused=0). 더 긴 연속 측정이 필요하면 회차
  사이에 TIME_WAIT 소멸 대기를 넣거나, k6 를 docker 망 안에서 실행(컨테이너↔컨테이너)해 호스트
  ephemeral 포트/NAT 자체를 안 쓴다. → **아래 "컨테이너 k6 측정"에서 이 방식을 구현·검증함.**

### 최종 측정 — 컨테이너 k6 (NAT·TIME_WAIT 둘 다 우회, 3회)

k6 를 app 과 같은 docker 네트워크에서 실행(`docker-compose.k6.yml`, BASE_URL=http://app:8080).
컨테이너↔컨테이너 통신이라 Windows 포트 프록시(NAT)도, 호스트 ephemeral 포트/TIME_WAIT 압박도
거치지 않는다. **호스트 JVM 우회판보다 실서버에 근접하고 refused 가 구조적으로 0.**

| 회차 | reserve_ok | oversell | http_reqs | win/lose | 예매 p50 | p90 | p95 | refused | HELD | AVAIL | SCARD | 합격? |
|------|-----------|----------|-----------|----------|---------|-----|-----|---------|------|-------|-------|-------|
| 1 | 1 | **0** | **2000** | 1/999 | 769ms | 1.47s | 1.59s | **0** | 1 | 999 | 999 | ✅ |
| 2 | 1 | **0** | **2000** | 1/999 | 896ms | 1.55s | 1.75s | **0** | 1 | 999 | 999 | ✅ |
| 3 | 1 | **0** | **2000** | 1/999 | 961ms | 1.92s | 2.16s | **0** | 1 | 999 | 999 | ✅ |

- **3회 전부 refused=0 + http_reqs=2000(진짜 1,000 동시 경쟁) + 1 win / 999 정상 패배 + oversell=0.**
  호스트 JVM 판(후반 회차 refused 잔여)보다 깨끗한 **L1 의 이상적 측정값**. 이 구성이 L2~L6 표준 인프라.
- **`http_req_failed` threshold(rate<0.01) 추가 후 재측정 3회 모두 0%(0/2000) 통과** = 999 패배자
  전원 깨끗한 409, 5xx 누수 0 을 자동 단언. (오버셀은 `reserve_ok==1` + DB 로 검증)
- **`entry_fail` threshold(count==0) 추가** — 입장 토큰 실패(429 차단/refused)를 집계해 "1,000 전원
  경쟁"이라는 전제가 깨지면 침묵 통과 대신 threshold 실패로 노출. 재측정 3회 모두 entry_fail=0 통과
  (입장 우회 max-active=2000 적용 상태에서 전원 토큰 획득 = 전제 성립 확인).
- p95(~2.4s)가 호스트 판(~1.4s)보다 큰 것은 측정 신뢰성의 대가 — 호스트 판은 refused 로 빠진
  요청만큼 실경쟁이 줄어 꼬리가 짧았다. 1,000 이 *전부* 직렬 임계구간을 통과하는 값이 이쪽이 정확.

#### 디버깅 여정 (컨테이너 k6 적용 시 만난 두 함정)

컨테이너 k6 로 전환하며 초기엔 **3회 모두 refused=2000**(전량 실패)이 났다. 두 독립 결함이 겹쳤다:

1. **AOT 캐시가 컨테이너 쓰기 레이어에만 존재 → force-recreate 마다 재 dump (~10s).**
   `docker-entrypoint.sh` 가 런타임에 `/app/app.aot` 를 생성하는데, 이는 이미지 레이어가 아니라
   컨테이너 쓰기 레이어라 **force-recreate 시 사라진다**(이전 커밋 메시지 "layer 안에 영구"는 오류).
   매 재생성마다 dump 단계 JVM(onRefresh 종료)→본 JVM 으로 **JVM 이 2번 떠**, health 가드가 dump
   단계에 잠깐 통과한 뒤 본 JVM 재시작 중 k6 가 출발 → refused.
   → **수정: named volume `aot_cache:/app/aot` 영속화 + jar 변경 시 자동 무효화**(jar -nt cache).
     검증: 2차 force-recreate 에서 `AOT cache 재사용`, 기동 5.5s(dump 생략).

2. **`docker compose run` 이 가드 통과한 app 을 재생성.** (refused 의 진짜 주범)
   k6 실행을 `docker compose -f yml -f k6.yml run k6` 로 할 때, app 을 만든 조합(`-f yml -f
   override`)과 **조합이 달라** compose 가 "현재 app 이 원하는 정의와 다름 → 재생성"으로 판단,
   **방금 준비된 app 을 재생성**(StartedAt 갱신)한다. depends_on(started) 이 준비를 보장 못 해
   k6 가 재시작 중 app 에 전량 refused. (app StartedAt 이 k6 run 전후로 바뀜을 직접 확인.)

   **1차 해법(증상 억제)**: `run --no-deps` — depends_on 무시. refused=0 은 되나 *의존관계를
   버리는* 회피책이라 구조적으로 부적절(k6 는 app 에 의존하는 게 맞다).

   **최종 해법(구조 개선, 2축)**:
   - **app healthcheck + k6 `depends_on: condition: service_healthy`** — compose 가 app 의
     *준비 완료*까지 k6 를 자동 대기시킨다. JRE 이미지엔 curl/wget/nc 가 없어 bash 내장
     `/dev/tcp` 로 actuator health 를 친다(`CMD-SHELL`=sh 라 `/dev/tcp` 불가 → `["CMD","bash",...]`).
     → 수동 health 폴링 제거, depends_on 유지.
   - **admission(K) 우회를 별도 override 파일 → base env 기본값**(`BOOKING_ADMISSION_MAX_ACTIVE:
     ${...:-100}`)으로 통합. 모든 compose 호출이 *동일 조합*을 쓰게 돼 재생성 트리거(조합 불일치)
     자체가 사라진다. `docker-compose.override.yml` 삭제, 우회는 `$env:BOOKING_ADMISSION_MAX_ACTIVE=2000`.
   → 검증: `--no-deps` 없이도 **app 재생성 안 됨 + refused=0 + http_reqs=2000 + win=1**.

> 교훈: (1) "health=UP" 만으로 측정 출발을 신뢰하지 말고 compose healthcheck(service_healthy)로
> *준비*를 보장한다. (2) `docker compose run` 의 재생성은 **조합 불일치**가 트리거 — 모든 호출이
> 같은 `-f` 세트를 쓰도록 환경변수로 토글을 통합하면 근본 차단된다. `--no-deps` 는 마지막 수단.

### 코드/스크립트 변경

| 파일 | 변경 |
|------|------|
| `load-tests/common/config.js` | BASE_URL 기본값 `localhost`→`127.0.0.1` (IPv6 해석 미스로 인한 RST 회피) |
| `load-tests/scenarios/L1_single_seat_race.js` | `handleSummary` 가 `return {}`(요약 억제) → `textSummary`(콘솔 표)+JSON 출력 복원. p95/p99·http_reqs 보존 |
| `load-tests/scripts/Run-L1-Host.ps1` | **신설** — 앱을 호스트 JVM(`java -jar`, JDK 25)으로 매 회 reset+재기동하는 L1 러너(NAT 우회). mysql/redis 는 Docker 유지(127.0.0.1:3308/:6379) |
| `docker-compose.k6.yml` | **신설** — k6 를 app 과 같은 docker 망에서 실행(profiles:[loadtest], BASE_URL=app:8080). `depends_on: app: condition: service_healthy` |
| `load-tests/scripts/Run-L1-Container.ps1` | **신설** — 컨테이너 k6 L1 러너. 단일 compose 조합(`-f yml -f k6.yml`), admission 은 env 주입, app healthy 가드, k6 `run`(--no-deps 불필요) |
| `docker-compose.yml` | ① app `aot_cache:/app/aot` 볼륨(AOT 영속화) ② app `healthcheck`(bash `/dev/tcp` actuator) ③ `BOOKING_ADMISSION_MAX_ACTIVE: ${...:-100}`(env 토글, override 파일 대체) |
| `docker-entrypoint.sh` | 캐시 경로 `/app/aot/app.aot`(volume), jar 가 캐시보다 최신이면 재 dump(무효화) |
| `docker-compose.override.yml` | **삭제** — admission 우회를 base env 기본값으로 통합(조합 불일치 제거). 우회는 `$env:BOOKING_ADMISSION_MAX_ACTIVE=2000` |

### 실행 방법 (호스트 JVM)

```powershell
# 1) bootJar 빌드 (1회)
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\openjdk-25.0.2"; ./gradlew bootJar -x test
# 2) 3회 반복 측정 (Docker app 자동 중지 → 호스트 JVM 재기동 → k6 → 정합성)
pwsh load-tests/scripts/Run-L1-Host.ps1 -Iterations 3
# 3) 끝나면 Docker app 복구
docker compose up -d app
```

> 컨테이너 k6 로 측정하려면 `Run-Scenario-Container.ps1 -Scenario load-tests/scenarios/L1_single_seat_race.js -PostRunCheck`
> (app 과 same-network 직결로 NAT 우회 → refused≈0). 당시 호스트 k6 → 포트매핑(`Run-L1.ps1`) 경로는
> Windows NAT 포화로 refused 다수라 폐기됨.

### 합격 기준 (S4 — 정합성) — 전부 충족
- k6 threshold: `oversell == 0` AND `reserve_ok == 1` ✅ (3회 Exit 0)
- DB: `reservation` 의 `seat_inventory_id=1` = HELD 1건, 다른 상태 0건 ✅
- DB: `seat_inventory.status=AVAILABLE` AND `schedule_id=1` = 999건 ✅
- Redis: `SCARD avail:1` = 999 (DB 와 정확히 일치 = 드리프트 0) ✅

> 이후 E1(선점/락) Before/After 의 **After 케이스 = 본 L1 결과**가 됨(T4-9 입력).

### 환경 메타

| 항목 | 값 |
|------|-----|
| CPU | Intel Core i7-9700 @ 3.00GHz (8C/8T) |
| RAM | 32GB |
| OS | Windows 11 Pro (26200) |
| Docker Desktop / Engine | Engine 29.5.2 (WSL2 backend) |
| 앱 실행 | **호스트 JVM** — Temurin/OpenJDK 25.0.2 (`java -jar` bootJar) |
| k6 ver. | v2.0.0 |
| MySQL / Redis | 8.0 / 7-alpine (docker-compose, 호스트 포트 3308/6379) |
| 앱 빌드 commit | 3c92c48 |

### 관찰·해석
S4(oversell=0) 를 1,000 VU HTTP 풀스택에서 3회 일관 달성 — Redis Set `SREM` 원자 선점 +
DB `@Version` 방어 조합이 단일 좌석 1,000 경쟁에서 정확히 1건만 통과시킴을 재현했다.
부수적으로, Windows+Docker Desktop 환경의 측정 함정(NAT 포화로 인한 부하 미달)을 격리 실험으로
규명하고 호스트 JVM 실행으로 우회해 *진짜 1,000 동시* 부하를 확보했다 — 측정 신뢰성 자체가
이번 T4-3 의 부가 성과.

---

## T4-4 — L2/L2b 정상·자동배정 처리량 (완료, 2026-06-19)

### 측정 환경 (L2·L2b 공통)
- **컨테이너 k6** (`docker-compose.k6.yml`, BASE_URL=http://app:8080) — T4-3 에서 확립한 표준.
  NAT·TIME_WAIT 우회로 refused=0. 실행기: `Run-Scenario-Container.ps1`.
- app: Docker 컨테이너(JDK 25 AOT 캐시), MySQL 8.0 / Redis 7. admission K=2000(env 우회).
- 매 회차 TRUNCATE+FLUSHDB → app force-recreate(재시드/워밍업) → health → k6.
- raw: `load-tests/results/L2_*`, `L2b_*` (gitignore). 앱 빌드 commit 3c92c48.

### 집계 단위 보정
L1/L2/L2b 시나리오에 `http.setResponseCallback(expectedStatuses(2xx, 409, 410[, 429]))` 적용 →
정상 비즈니스 응답(경쟁 패배 409 / 매진 410 / 입장 제어 429)을 `http_req_failed` 에서 제외했다.
따라서 **`http_req_failed` 는 SLO(5xx<1%) 와 동일 의미**(진짜 서버 오류/연결 실패만 집계).

---

### L2 — 정상 예매 혼합 부하 (조회 70% / 입장+예매 25% / 확정 5%)

프로파일: 0→1,000 VU ramp-up 2분 → 1,000 유지 5분 → ramp-down 1분 (총 8분), 3회.

| 지표 | SLO | 회차1 | 회차2 | 회차3 | 판정 |
|------|-----|------:|------:|------:|:----:|
| **예매 p95** (`type:reserve`) | ≤ 500ms | 408ms | 378ms | 348ms | ✅ |
| 예매 max (p99 참고) | ≤ 1s | 1.34s | 2.13s | 1.50s | △(꼬리) |
| **조회 p95** (`type:list`) | ≤ 200ms | **996ms** | **960ms** | **911ms** | ❌ |
| **TPS** (http_reqs/s) | ≥ 200 | 833 | 847 | 863 | ✅ |
| **5xx** (http_req_failed) | < 1% | 0.03% | 0.03% | 0.04% | ✅ |
| interrupted iters | 0 | 0 | 0 | 0 | ✅ |
| 총 iterations | — | 416,869 | 424,660 | 432,977 | — |

- **예매 p95 348~408ms — 핵심 booking 경로 SLO 합격.** TPS 833~863 으로 목표(200)의 4배 이상.
  (S3 TPS 는 `http_reqs: rate>200` threshold 로 자동 단언하도록 게이트화.)
- **5xx 0.03~0.04%** — 보정된 집계라 진짜 서버 오류. SLO 통과. (429/409/410 제외가 의도대로 동작.)
- **조회(list) p95 ~0.9s 로 SLO(200ms) 대폭 미달 → k6 threshold crossed.** 이번 T4-4 의 핵심 발견.

#### list p95 미달 — 원인 가설과 후속 (측정 후 최적화)
버그가 아니라 측정이 드러낸 설계 이슈. 두 가지 가설:
1. **조회 캐시 미적용/비효율** — 설계상 schedule-list 는 Redis 단기 캐시로 p95≤200ms 를 노려야
   하나(2-tier 일관성), 현재 매 요청이 DB 를 칠 가능성. → **실험 E3(조회 캐시 on/off, T4-9)의
   Before 데이터**로 직결.
2. **부하 경합** — 1,000 VU 가 booking 임계구간(Redisson 락)과 자원을 다투며 조회 응답까지 밀림
   (avg http_req_duration 260~296ms 를 list 가 끌어올림).

→ "측정 후 최적화" 원칙에 따라 **L3(조회 폭주, T4-5)·E3(T4-9)에서 본격 분석.** L2 에서 Before 가
선확보된 셈. 현 단계 판정: **예매·처리량·5xx 합격, 조회 지연 미달(사유·후속 명시).**

---

### L2b — 자동 배정(AUTO) 처리량·매진 정합성

프로파일: `shared-iterations` 1,000 VU × 2,000 iter (좌석 1,000 보다 많이 시도 → 매진 수렴), 3회.

| 지표 | 회차1 | 회차2 | 회차3 | 판정 |
|------|------:|------:|------:|:----:|
| **reserve_ok** (201 성공) | 1000 | 1000 | 1000 | ✅ =좌석 수 |
| **sold_out** (410) | 1000 | 1000 | 1000 | ✅ 초과분 전량 매진 |
| checks (unexpected) | 100%(2000/0) | 100% | 100% | ✅ |
| http_req_failed | 0.00% | 0.00% | 0.00% | ✅ |
| 예매 p95 | 5.81s | 4.96s | 6.25s | △(최악 경합) |
| 실효 처리량 reserve_ok/s | 97 | 109 | 108 | — |

**정합성 (Invoke-PostRunCheck, 3회 동일):**
중복 HELD/CONFIRMED **0행**, 스케줄 AVAILABLE **0**(완전 매진), HELD 만료 잔재 **0**,
Redis `SCARD avail:1` **0**.

- **AUTO 모드 매진 정합성 완벽** — 1,000석에 2,000요청 → 정확히 1,000 성공 + 1,000 매진(410),
  oversell 0. `SPOP` 기반 원자 선점이 AUTO 경로에서도 정확함을 HTTP 풀스택으로 입증(L1 의 SEAT/SREM
  검증을 AUTO/SPOP 로 확장).
- **PostRunCheck 라벨 주의:** SQL 이 L1 전용(`seat_inventory_id=1` 단일 좌석)이라 `① HELD=1` 은
  *그 한 좌석*의 값일 뿐. **L2b 매진의 진짜 증거는 `④ AVAILABLE=0` + `SCARD=0`.** (라벨 일반화는
  별도 백로그.)
- **예매 p95 ~5s 는 의도된 최악 경합** — `shared-iterations` 로 1,000 VU 가 동일 스케줄 락 임계구간을
  직렬 통과(L1 과 동성격). L2b 목적은 처리량 SLO 가 아니라 **AUTO 매진 정합성**이므로 threshold
  crossing 은 정상. 현실적 처리량은 혼합 부하인 L2(TPS 833~863)가 대표값.

### 관찰·해석 (T4-4 종합)
정상/자동배정 부하에서 **예매 경로·처리량·정합성은 모두 합격**(예매 p95≤500ms, TPS≥200×4,
oversell 0, 매진 정확 수렴). 유일한 미달은 **조회(list) 지연**으로, 이는 2-tier 일관성 모델의
조회 캐시 효과를 검증할 L3·E3 의 Before 신호로 활용한다.

---

## T4-6 — L4 입장 초과 (B-2 버그 수정 검증, smoke 1회 그린)

> 상태: smoke(`-Iterations 1`) 그린으로 **B-2(좌석 재예매 불가) 수정**을 검증. 본 측정(3회 공식 수치)은
> 실측자 책임(본 문서 사용법). 발견 경위·수정 설계는 `docs/plans/Seat_Rebooking_Unique_Constraint_Fix_Plan.md`.

### 배경 — 왜 L4 가 깨졌나
L4 는 슬롯 churn(입장→예매→1~2s 점유→**취소**→슬롯/좌석 반환)으로 입장↔거절 steady state 를 만든다.
취소로 되돌아온 좌석이 `SPOP` 로 재배정돼 **재예매**되는데, `reservation.seat_inventory_id` 의
*상태-무관 전역 유니크*(`UKqjf4…`) 때문에 재예매 INSERT 가 `Duplicate entry` → 500 이 났다.

### 수정 (A-2 활성 한정 부분 유니크)
- 스키마 관리 **ddl-auto → Flyway** 전환(`V1__baseline`=기존 스키마, `V2__active_seat_unique`).
- `V2`: 생성 컬럼 `active_seat_inventory_id`(활성 HELD/CONFIRMED 일 때만 좌석id, 아니면 NULL) + 그 위
  `uk_active_seat`. MySQL 유니크의 NULL 중복 허용 → **활성은 좌석당 1건(오버셀 DB 방어선 유지)**,
  취소/만료(NULL)는 공존 → **재예매 가능**. 기존 전역 유니크는 제거(FK 용 일반 인덱스 선생성 후 DROP).

### Before / After (K=100, RATE=500 TPS, 3분 + 램프)
| 지표 | Before (수정 전, 계획서 §1 증거) | After (수정 후 smoke) | 판정 |
|------|------|------|------|
| `server_errors` (예매 5xx) | **100** (Duplicate entry) | **0** | ✓ |
| `http_req_failed` (entry/전체) | 5xx 누수 | **0.00%** (0/134,799) | ✓ |
| `admission_reject_rate` | — | **85.80%** (>0.5) | ✓ |
| `dropped_iterations` | — | **0** | ✓ |
| reserve `p(95)` | — | **88.69ms** (<500) | ✓ |
| k6Exit | 99 (threshold 위반) | **0** | ✓ |

- **핵심**: churn 이 3분 내내 재예매를 시켰는데 5xx 0 — 되돌아온 좌석 재예매가 정상화됐다.
- **부수 효과 해소**: Before 의 5xx 100 건은 입장 슬롯 100 개를 누수시켜 K 조기 포화·입장 급감을
  유발했었다. 수정으로 5xx 가 사라져 이 경로의 누수도 사라짐. (단 "입장 후 예매 미생성 시 슬롯 회수"
  일반 케이스는 독립 결함 → 백로그 **B-3**.)
