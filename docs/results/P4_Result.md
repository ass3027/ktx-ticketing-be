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
| T4-5 L3 조회 폭주 | ⏳(측정중) | **Before 규명 + ①② 완료**: 3,000TPS 목표에서 포화. 병목=커넥션 점유시간. **① pool 5배=+14%**(지렛대 아님). **② SCARD tx밖: usage_mean 6.9→2.6ms(−63%)·처리량 +62%·acquire −98%** — 최대 단일 지렛대(근본=점유시간 확증). 단 여전히 SLO 미달 → ③④ 진행 |
| T4-6 L4 입장 초과 | ✅ | **본 측정 3회 일관 그린**(2026-06-24, K=100·RATE=500): server_errors 0(수정 전 100)·reject 85.7~85.8%·reserve p95 69~97ms·dropped 0·k6Exit 0. B-2 수정 확정 |
| T4-7 L5 임계점 탐색(K 확정) | ⏳ | `booking.admission.max-active` 잠정값 100 → L5 결과로 확정 |
| T4-8 L6 지속 부하(soak) | ✅ | **완료**(2026-06-30): 부하 SLO 그린 + 정합성 게이트 green(L6_after K=2000: violation 0·k6Exit 0) + 시계열 우상향 없음(steady p95 기울기 −8.4ms/min, 하향 안정). 잔여 2건(B-1 해소 후 게이트·시계열 판정)을 T4-13 측정으로 해소 |
| T4-9 E1·E2·E3 Before/After | ⏳ | E1-before 토글(`booking.preemption.enabled=false`) 구현 필요 |
| T4-10 E5 가상 스레드 | ⏳ | |
| T4-11 E6 분산 락 라이브러리 비교 | ⏳ | `DistributedLock` 추상화는 완료 |
| T4-12 E7 선점 백엔드(Redis vs Memcached) | ⏳ | `SeatPreemption` 추상화는 완료 |
| T4-13 만료 sweep 벌크 최적화 | ✅ | **Before/After 입증**(L6 1회씩, 2026-06-30): backlog 21,377→0·consistency_violation ✗→✓·k6Exit ≠0→0. reserve p95 47→64ms(둘 다 <500). 오버셀 0 유지 |

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

## T4-5 — L3 조회 폭주 (⏳ 측정 중 · 조회 경로 최적화)

> 상태: **Before 규명 + ① pool sweep + ② tx밖 완료**(② 2026-07-04). ③(pipeline)·④(캐시)는 진행.
> 설계·진행 로그: `docs/plans/Query_Path_Optimization_Plan.md`.
> 프로파일: 열린 루프(ramping-arrival-rate) 0→3,000 TPS ramp 1m → 3m 유지 → 30s down.
> `dropped_iterations==0` 게이트로 "생성기가 목표 도착률을 실제 발사했는가"를 전제 단언.

### Before — 순수 읽기 부하가 ~850 TPS 에서 포화 (3회 일관)

| 지표 | SLO | 회차1 | 회차2 | 회차3 | 판정 |
|------|-----|------:|------:|------:|:----:|
| 조회 p95 (`type:list`) | ≤ 200ms | 9.46s | 9.65s | 11.98s | ❌ |
| dropped_iterations | == 0 | 458,973 | 456,830 | 487,091 | ❌ |
| 실효 http_reqs/s | (목표 3,000) | 781 | 783 | 686 | — |
| http_req_failed | < 1% | 0% | 0% | 0% | ✅ |

- **목표 3,000 TPS 미발사**(`Insufficient VUs, reached 5000` + dropped 46만) → 관측 p95 는 부하 모델이
  무너진 값, **"~800 TPS 에서 포화한다"는 사실**이 신호(절대치 아님).
- **병목 규명(Prometheus 실측)**: HikariCP `max=10`(기본·미튜닝)·`active=10`(포화)·`pending=190`·
  `acquire max=3.9s`·`usage max=644ms`. 즉 조회가 `@Transactional` 안에서 **SCARD 를 페이지 편수만큼
  직렬 왕복**하는 동안 DB 커넥션을 점유 → 풀 회전율 저하 → 대기 폭발. (§Plan §1.2)

### ① DB pool size sweep — pool 은 지렛대가 아님 (독립 A1, pool×2회)

baseline(SCARD 직렬·tx안·캐시off)에서 `DB_POOL_SIZE` 만 토글. 러너가 actuator
`hikaricp.connections.max` 로 실제 반영을 검증(옛 풀로 도는 측정 무효 방지).

| pool | TPS 회1 | TPS 회2 | 평균 TPS | 증분 | list p95 | pending | acquire max | usage max |
|-----:|------:|------:|------:|:---:|------:|------:|------:|------:|
| **10**(Before) | 840.8 | 857.8 | **849** | — | ~8.9s | 190 | 3.62s | 0.843s |
| **20** | 918.6 | 928.9 | **924** | +8.8% | ~8.1s | 179 | 3.17s | 0.693s |
| **30** | 950.5 | 950.4 | **950** | +2.8% | ~8.1s | 170 | 2.97s | 0.615s |
| **50** | 959.9 | 972.5 | **966** | +1.7% | ~7.5s | 149 | 2.44s | 0.206s |

- **교과서적 한계효용 체감**: 10→20 +8.8%, 20→30 +2.8%, 30→50 +1.7%. pool 을 **5배 올려도 TPS
  849→966(+14%)에 그치고 ~960 TPS 로 점근**. 모든 pool 에서 여전히 threshold crossed(SLO 미달).
- **pending·acquire 가 pool 을 늘려도 149·2.44s 로 여전히 높다** — 커넥션이 usage(점유) 동안 붙잡혀
  대기가 근본 해소되지 않음. **근본 병목은 pool 크기가 아니라 커넥션 점유시간**(=Redis 왕복을 tx 안에서
  기다림)임을 정량 입증.
- 환경 제약 정합: MySQL 컨테이너 **2코어**(HikariCP 공식 이론값 `(2×2)+1≈5`). pool 상향의 실효는
  2코어 병렬 한계에 묶임 → "pool 을 늘리면 되지 않나"라는 접근을 수치로 반박하는 Before 근거.
- **후속**: ②(Redis 를 tx 밖으로)·③(pipeline)·④(캐시=E3 after)가 usage 를 낮춰 포화점을 올리는지 측정.

### ② Redis(SCARD)를 tx 밖으로 — 커넥션 점유시간이 진짜 지렛대 (독립 A2, pool10 고정·각 3회)

같은 pool(10)에서 `BOOKING_QUERY_REDIS_OUTSIDE_TX` 만 off→on 토글해 **같은 세션**에서 L3 재측정
(off=Before=SCARD 가 `@Transactional` 안, on=After=SCARD 가 tx 밖). 러너가 컨테이너 주입값을 검증.
usage 는 **평균**(sum/count 증분)으로 본다 — `usage_seconds_max` 는 드문 outlier(부팅·GC 등)에 지배돼
점유시간 변화를 못 드러낸다(off 0.407s vs on 0.389s 로 거의 불변). 점유시간의 실체는 평균.

| 지표(3회 대표) | off=Before(SCARD in tx) | on=After(SCARD out of tx) | Δ |
|------|------:|------:|:---:|
| **usage_mean**(커넥션 점유시간) | 6.92 ms | **2.58 ms** | **−63%** |
| acquire_mean(획득 대기) | 133.5 ms | **2.30 ms** | −98% |
| acquire max | 1.48 s | 0.24 s | −84% |
| pending max(대기 큐) | 190 | 77 | −59% |
| active | 10 | 10 | = |
| **실효 http_reqs/s** | 1,230 / 1,250 / 1,237 | 1,995 / 2,001 / 2,009 | **+62%** |
| list p95 | 4.19 / 4.04 / 4.12 s | 2.30 / 2.35 / 2.36 s | −43% |
| dropped_iterations | ~340,000 | ~133,000 | −61% |

- **Little's law 로 정합**: 포화 시 처리량 ≈ pool / usage_mean. active(10)·pool(10) 고정에서 점유시간을
  6.92→2.58ms(−63%) 로 줄이자 처리량이 1,237→2,000/s(+62%) 로 거의 같은 비율 상승. **커넥션이 Redis
  왕복을 tx 안에서 기다리던 시간**이 사라져 풀 회전율이 오른 것 — ① 이 지목한 "근본=점유시간" 을 직접 입증.
- **① 대비 지렛대 크기**: pool 5배(+14%) vs tx밖(+62%). **pool 은 곁가지, tx 경계가 본질**이라는 ①의 결론을
  ② 가 정량 확증. acquire 대기가 133→2.3ms(−98%) 로 붕괴한 게 그 직접 증거(커넥션이 빨리 반환됨).
- **정합성**: 잔여석·매진 판정 불변(같은 avail Set SCARD, 위치만 이동). `ScheduleQueryPathIntegrationTest`
  가 실 DB/Redis 로 on/off 응답 등가 + tx-밖 detached `getTrain()` 안전(fetch join)을 회귀 가드.
- **한계**: on 도 여전히 dropped>0·p95≫200ms → **②만으론 SLO(200ms) 미달**. 3,000 TPS 목표에 아직 포화
  (~2,000/s). ③(pipeline)·④(캐시)로 SCARD 왕복 자체를 더 줄여야 함. ② 는 지금까지 최대 단일 지렛대.
- **주의(세션 드리프트)**: 이 in-session off baseline(1,237/s·p95 4.1s)은 §1 원 Before(781/s·p95 8.9s)와
  절대값이 다르다 — 포화 처리량은 머신 상태에 의존하고 두 측정이 다른 시점이라서다. 그래서 ②는 §4.1 원칙대로
  **같은 세션 off↔on** 을 비교했다(절대값이 아니라 토글 델타가 결론). avail 은 정상 워밍(대부분 스케줄 SCARD>0).

---

## T4-6 — L4 입장 초과 (본 측정 3회 그린, ✅)

> 상태: **본 측정 3회 일관 그린**(2026-06-24, 컨테이너 러너 K=100·RATE=500·3분+램프). smoke 1회로
> 검증했던 **B-2(좌석 재예매 불가) 수정**이 3회 측정으로 확정됐다. 발견 경위·수정 설계는
> `docs/plans/Seat_Rebooking_Unique_Constraint_Fix_Plan.md`.

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

### 본 측정 3회 (K=100, RATE=500 TPS, 3분 + 램프, 컨테이너 러너 — 2026-06-24)
| 회차 | `server_errors` | `http_req_failed{type:entry}` | `admission_reject_rate` | `dropped_iterations` | reserve `p(95)` | k6Exit |
|------|------|------|------|------|------|------|
| 1 | 0 | 0.00% (0/104,999) | 85.72% | 0 | 69.08ms | 0 |
| 2 | 0 | 0.00% (0/104,999) | 85.83% | 0 | 97.05ms | 0 |
| 3 | 0 | 0.00% (0/104,999) | 85.81% | 0 | 79.33ms | 0 |
| **합격선** | <10 | <1% | >0.5 | ==0 | <500ms | 0 |

- 3회 모두 전 threshold 충족 (refused=0, http_reqs≈134.8K/회). 초과분이 ~85.8% 로 429 흡수되고
  예매 경로 진짜 5xx 는 0 — 입장 제어(S5)가 일관되게 동작함을 본 측정으로 확정.
- reserve p95 의 회차 변동(69~97ms)은 모두 SLO(500ms) 대비 큰 여유 안에 있어 합격 판정에 영향 없음.

---

## T4-8 — L6 지속 부하 (soak) (✅ 완료, 2026-06-30)

> 상태: ✅ 완료. 1차(300VU·33분·K=100, 2026-06-24)에서 **U-2 정합성 자동 게이트**(`/internal/consistency`
> teardown audit)를 처음 실측 적용 — 부하 SLO 전부 그린이나 게이트가 기존 B-1(timezone skew) 검출(k6Exit 99).
> 잔여였던 ① B-1 해소 후 게이트 green 최종 확인, ② 응답시간 시계열 우상향 판정 모두 **T4-13 측정(L6_after,
> K=2000, 2026-06-30)으로 해소** — 아래 [완료 확인] 참조.

### 부하 지표 (전부 그린)
| 지표 | 값 | 합격선 | 판정 |
|------|------|------|------|
| `http_req_failed` (진짜 5xx) | **0.00%** (0/433,790) | <1% | ✓ |
| `checks` (confirm/list) | **100.00%** (252,444/0) | >99% | ✓ |
| reserve `p(95)` | **57.18ms** | <500 | ✓ |
| reserve `p(99)` | <1,000 (threshold ✓) | <1,000 | ✓ |
| list `p(95)` | **9.84ms** | <200 | ✓ |
| `entry_shed` (입장 차단) | 140,927 | 관측 | 입장 제어 동작 |
| `sold_out` | **0** | 관측 | 50 스케줄 분산 충분 |
| `dropped`/`interrupted` | 0 | — | 부하 안정 |

### 정합성 게이트가 검출한 것 — B-1(tz skew), 오버셀 아님
| audit 항목 | 값 | 의미 |
|------|------|------|
| `availDrift` | **0** | Redis 가용 풀 ↔ DB 드리프트 없음 |
| `statusViolation` | **0** | 좌석당 활성 2건↑ 없음 = **오버셀/중복 0** |
| `expiredHeld` | **≈3,700** | 만료 미회수로 *보이는* HELD — 실제론 tz skew 오판 |

- **근본 원인 = B-1 timezone skew**(백로그 기록의 가설이 환경 의존 실결함으로 확정):
  컨테이너에서 app JVM 기본 tz=KST, MySQL 세션 tz=SYSTEM(UTC) 불일치. `expiresAt` 이 KST 벽시계
  (예: `13:42`)로 naive 저장돼 — DB `NOW()`(UTC `05:05`) 기준으론 **미만료**(`is_expired=0`,
  실측 `SELECT (expires_at<NOW())` 로 확인)지만, audit 의 앱 `Clock`(KST) 기준으론 만료로 집계됨(9h skew).
- **U-2 기능 관점에선 의도된 성공**: soak audit 게이트가 *진짜* 정합성 결함(B-1)을 자동 검출했다.
  오버셀·드리프트(`availDrift`·`statusViolation`)는 0 이라 좌석 정합성 자체는 건전.
- **B-1 해소(2026-06-24, 경로 A)**: 환경 tz 를 전부 KST 로 통일(app/mysql `TZ: Asia/Seoul`,
  entrypoint `-Duser.timezone=Asia/Seoul`, JDBC `serverTimezone=Asia/Seoul` 유지)해 skew 제거.
  검증: 예매 1건의 `expires_at`·DB `NOW()` 모두 KST → `is_expired=0`·audit `expiredHeld=0`
  (이전 9h skew 재현 없음). 이 red 의 원인은 제거됨 — L6 재측정 시 게이트 green 기대.
### [완료 확인] 잔여 2건 해소 (T4-13 측정 L6_after, K=2000, 2026-06-30)
- **① B-1 해소 후 게이트 green 최종 확인 ✅**: L6_after 에서 `consistency_violation=0`(threshold ✓)·
  k6Exit=0. 게다가 1차(K=100, 입장 제어가 부하를 깎음)보다 **더 가혹한 K=2000(입장 제어 우회)** 조건에서
  green 이라 더 강하게 충족. (B-1 자체는 [[B-1]] 에서 환경 tz KST 통일로 해소됨)
- **② 응답시간 시계열 우상향 판정 ✅ — 우상향 없음(오히려 하향 안정화)**: `L6_after_dashboard.html`
  임베드 시계열(10s 스냅샷 ×231)을 디코딩해 `http_req_duration` p95 추세 분석:

  | 구간 | p95 (ms) | iteration_duration p95 (ms) |
  |------|------|------|
  | 0–5m | 68.4 | 2,098 |
  | 5–10m | **426.1** (워밍업 스파이크) | 2,565 |
  | 10–15m | 74.6 | 2,101 |
  | 15–20m | 33.2 | 2,055 |
  | 20–25m | 34.9 | 2,054 |
  | 25–30m | 22.0 | 2,031 |

  - steady-state(VUs=300, 0–1907s) p95 **선형 회귀 기울기 = −8.4 ms/min(음수=우하향)**.
  - 5–10m 의 일시적 스파이크는 JIT/AOT 워밍업·캐시 채워지는 과도구간이며, 이후 평탄·하향 수렴.
    33분 soak 에서 **메모리 누수/성능 열화로 인한 우상향 추세는 관측되지 않음** → soak 합격.
  - (주의: 시계열은 base `http_req_duration` 전체값 — list 다수라 reserve 전용보다 낮게 나오나
    *추세* 판정에는 동일하게 유효. reserve 전용 태그 시계열은 dashboard 스냅샷에 미수집.)

> ⚠️ teardown 함정(이번에 해소): k6 기본 `teardownTimeout=60s`. teardown 이 HELD TTL 수렴을
> 기다리느라(330s) 60s 에 강제 종료되면 audit 이 아예 호출되지 않아 `consistency_violation=0`(위양성
> 통과)로 보인다(직전 1회차에서 실제 발생). L6 `teardownTimeout: '360s'`·L5 `'150s'` 로 수정.

## T4-13 — 만료 sweep 부수효과 배치화 (✅ Before/After 입증, 2026-06-30)

> 상태: 만료 sweep 의 부수효과 발사를 **건별(per-row) → 배치(bulk)** 로 바꾼 최적화의 효과를 L6
> soak(300VU·~33분·K=2000) Before/After 1회씩으로 실측. 전략은 `booking.expiry.batch-side-effects`
> 토글(`ExpirySideEffects` 건별/배치)로 분기, 런타임에 컨테이너 env 주입값으로 검증(옛 설정 무효측정 방지).
> Before 산출물은 `load-tests/results/L6_before_results.zip`(`.gitignore` 대상이라 git 미전달).

### 측정 조건
| | Before (건별) | After (배치) |
|------|------|------|
| `BOOKING_EXPIRY_BATCH_SIDE_EFFECTS` | `false` | `true` |
| `BOOKING_EXPIRY_BATCH_SIZE` | 100 | 1000 |
| `BOOKING_EXPIRY_SWEEP_INTERVAL` | 30s | 2s |
| 시나리오 | L6_soak (300VU, 33분, K=2000) | 동일 |
| testRunDuration | 2,322s | 2,307s |

### Before / After
| 지표 | Before (건별) | After (배치) | 판정 |
|------|------|------|------|
| `expired_held_backlog` | **21,377** | **0** (메트릭 미발생) | 만료 유입을 sweep 이 완전 따라잡음 |
| `consistency_violation` | **21,377** (threshold ✗) | **0** (threshold ✓) | backlog 전량 해소 |
| k6 exit code | **≠0** (`thresholds … crossed`) | **0** | 게이트 통과 |
| `sold_out` | 77,538 | 61,697 | backlog 미해소분만큼 Before 가 매진 과집계 |
| reserve `p(95)` | 47.1ms | 64.3ms | 둘 다 <500 ✓ (배치 sweep 의 주기적 벌크 부하로 소폭 상승) |
| reserve `p(99)` | <1,000 (threshold ✓) | <1,000 (threshold ✓) | 둘 다 SLO 충족 |
| list `p(95)` | 14.8ms | 21.5ms | 둘 다 <200 ✓ |
| `http_reqs` | 561,064 | 602,217 | backlog 미발생으로 예매 경로 처리량 ↑ |
| confirm checks | 27,557 | 48,006 | After 가 확정까지 더 많이 성공 |

### 해석
- **핵심 = backlog 21,377 → 0**: Before(건별)는 sweep 이 만료 HELD 를 회수하는 속도가 신규 만료
  유입을 못 따라가 backlog 가 누적됐다. 이 누적분이 그대로 `consistency_violation`(전량 expiredHeld
  발, `availDrift`·`statusViolation`=0)으로 잡혀 k6 threshold red. 배치화 후 backlog 가 발생조차
  하지 않아(메트릭 카운터 0 증분) 게이트 green·k6Exit 0.
- **오버셀은 양쪽 다 0**: Before 의 violation 은 전부 *만료 회수 지연* 이지 좌석 중복 점유가 아니다
  (`availDrift`·`statusViolation`=0, [[T4-8]] 와 동일 성격). 즉 배치화는 정합성을 깨지 않고
  *처리 적체* 만 제거한 최적화다.
- **reserve p95 47→64ms 의 트레이드오프**: 배치 sweep 은 2s 주기로 최대 1,000건을 한 번에 처리하므로
  그 순간 DB 부하가 스파이크처럼 몰려 예매 경로 지연이 소폭 상승한다. 그래도 SLO(<500/<1000) 는
  여유 있게 충족 → backlog 제거 대비 수용 가능한 비용.
- **B-1 무관**: 본 Before 의 backlog 는 [[T4-8]] 의 tz skew(B-1, 해소됨)와 다른, sweep 처리량
  병목 그 자체다. T4-13 최적화의 정당성이 실측으로 입증됨.

### 수용 기준 (3종 충족)
- ✅ O(1) RTT: 부수효과를 건별 N회 발사 → 배치 1회로 묶음(전략 분리, `306d188`)
- ✅ 오버셀 0: `availDrift`·`statusViolation` 양쪽 측정 모두 0
- ✅ Before/After 수치: backlog 21,377 → 0, consistency_violation threshold ✗ → ✓, k6Exit ≠0 → 0

> 산출물: Before `load-tests/results/L6_before_results.zip`(summary/run_log/dashboard 3종) ·
> After `L6_after_summary.json`/`L6_after_container_run_1.txt`/`L6_after_dashboard.html`.
