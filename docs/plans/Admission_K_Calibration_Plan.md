> 상위 문서: `KTX_Ticketing_Architecture_and_Verification_Goals.md` (SLO S6·실험 E2)
> 출처: 2026-07-09 세션 — T4-7(L5 임계점 탐색 → 활성자 상한 K 역산·확정)
> 상태: **완료(2026-07-10)** — L5b 예매경로 격리 측정으로 임계점 확정 → **K=100(스케줄당) 확정**(§9).
>        safe_TPS≈150 × W≈0.9s × 마진0.75 ≈ 100. 정합성 audit 0(오버셀 0). 전역 K 는 후속 과제(§4).
> 연관: T4-7(L5)·T4-9(E2)·T3-3(활성자 카운터)·`AdmissionProperties`·P4_Result.md

# 활성자 상한 K 역산·확정 작업 기록 (Admission K Calibration)

## 0. 한 줄 요약

입장 제어의 동시 활성 세션 상한 **K(`booking.admission.max-active`, 현재 잠정값 100)**를
**L5 임계점 탐색(계단식 도착률 500→1k→2k→4k TPS)** 결과로 역산·확정한다. 임계점 = "달성 TPS가
목표를 못 따라가고 reserve p99 급증·백프레셔(429/503) 시작"하는 지점. 그 안전 처리량에서
**Little's law(`K ≈ 안전TPS × 평균 세션 보유시간`)**로 K를 역산한다. K는 코드가 아니라 설정값이라
확정 후 `application.yml` 한 줄 + 주석 근거만 바꾼다(코드 변경 0).

## 1. 배경 — 왜 K를 측정으로 정하는가

- **K는 지금 근거 없는 잠정값(100)**이다. `AdmissionProperties.maxActive` 주석·`application.yml`
  둘 다 "L5에서 역산 전까지의 잠정값"으로 명시돼 있다. 포트폴리오 평가 기준(C2·C6)상 **모든 SLO/설정
  값은 측정 근거가 있어야** 하며, K는 그 대표 사례다.
- **K의 의미**: 활성 세션이 K에 도달하면 신규 입장을 429로 거절(백프레셔)해 **코어 예매 경로가
  무너지지 않게 보호**한다. K가 너무 크면 보호가 안 돼 임계점 초과 시 5xx·p99 폭발, 너무 작으면
  멀쩡한 서버가 놀면서 불필요하게 거절한다. **적정 K = 서버가 SLO를 지키며 소화 가능한 동시성의 상한.**
- **`active:{scheduleId}` 는 스케줄별 카운터** → 현재 K는 **스케줄당 상한**이다(`AdmissionService`).
  전역 상한(active:global)은 없다. 이 단위 문제는 §4에서 측정 후 결정한다.

## 2. 측정 인프라 (기구축 — 재사용)

새 코드 작성 불필요. 다음이 이미 있다:

| 자산 | 역할 |
|------|------|
| `load-tests/scenarios/L5_stress.js` | ramping-arrival-rate 500→1k→2k→4k(각 3분 유지). churn(예매→1s 점유→취소)+50스케줄 분산으로 재고 소진 회피. `backpressure` Rate(429/503 비율=K 역산 입력)·`consistency_violation` Counter 계측. `http_req_duration{type:reserve\|list}` 태그 분리로 경로별 p99 노출 |
| `load-tests/scripts/Run-Scenario-Container.ps1` | 컨테이너 k6 러너(NAT·TIME_WAIT 우회). K를 `-AdmissionMax` 로 주입·검증. `-Dashboard` 로 시계열 HTML export |
| `/internal/consistency` audit | teardown 이 reconcile(60s)+sweep(30s) 수렴 대기 후 정합성 위반 합산 → threshold `consistency_violation==0` |

## 3. K 역산 방법론 (측정 → 숫자)

### 3.1 임계점 판정 (S6)
L5는 **열린 루프(도착률 고정 계단)**라, 각 plateau(500/1k/2k/4k TPS)에서 다음을 시계열로 끊어 본다:

1. **달성 TPS(`http_reqs`/s)가 목표 도착률을 따라가는가** — 못 따라가고 `dropped_iterations`가
   쌓이기 시작하면 그 직전 단계가 소화 한계. (단, dropped 가 서버 한계인지 LG/NAT 한계인지 §5로 구분.)
2. **reserve p99 급증** — `http_req_duration{type:reserve}` 가 SLO(p99<1s)를 깨고 꺾이는 단계.
3. **백프레셔 시작점** — `backpressure`(429/503) rate 가 0에서 유의미하게 오르는 단계 = 입장 제어가
   실제로 발동해 초과를 흡수하기 시작.
4. **진짜 5xx** — `http_req_failed`(409/410/429/503 제외 보정)가 오르면 그 단계는 이미 붕괴.

→ **임계점 = 위 지표가 동시에 꺾이기 직전의 "안전 처리량(safe TPS)"**. (예: 2,000은 그린인데
   4,000에서 p99·dropped 급증이면 안전 처리량은 2,000~4,000 사이, 보수적으로 2,000 채택.)

### 3.2 Little's law 역산
정상상태에서 **동시 활성 세션 수 ≈ 도착률 × 평균 세션 보유시간**이다(L = λ × W).

```
K ≈ safe_TPS × avg_session_hold_time
```

- `safe_TPS` = §3.1의 안전 처리량(서버가 SLO 지키며 소화하는 초당 입장·예매 요청 수).
- `avg_session_hold_time` = 입장(EntryToken 발급)부터 세션 종료(예매 확정/취소/만료)까지 평균 점유
  시간. L5 모델은 예매 성공 후 `HOLD_SECONDS=1`s 점유 후 취소 → 실측 홀드는 **입장~취소 왕복 지연 +
  1s** 정도. 실제 값은 측정 로그(reserve 응답시간 + hold)로 확정한다. 운영 가정(실사용자 결제 숙고
  시간)이 다르면 그 값을 별도로 밝혀 K를 재역산한다.
- **단위 정합**: safe_TPS 가 **전역** 처리량이면 K도 **전역 상한**이어야 하고, 스케줄당으로 나누려면
  `safe_TPS / 동시 인기 스케줄 수` 를 써야 한다. 이 단위 결정이 §4의 핵심 판단.

### 3.3 안전 마진
역산 K를 그대로 쓰지 않고 **안전 마진**을 적용한다(임계점은 붕괴 직전이므로 상한을 그 아래에 둔다).
관례상 안전 처리량 자체를 임계점의 70~80%로 잡거나, 역산 K에 0.7~0.8을 곱한다. 채택 계수와 사유를
결과 문서에 명시(과보호 vs 자원 활용 트레이드오프).

## 4. 열린 결정 — K의 단위 (측정 후 결정, 2026-07-09 사용자 지시)

**현 구조는 스케줄당 K인데 L5 임계점은 전역 처리량**이라 역산 단위가 어긋난다. 세 갈래:

- **(가) 스케줄당 K 유지(현행)** — `active:{scheduleId}` 그대로. `K = safe_TPS / 동시부하 스케줄 수 ×
  hold`. 코드 변경 0. 단 소수 인기 노선에 쏠리면 전역 보호가 약함.
- **(나) 전역 K 추가** — `active:global` 카운터 추가로 전체 동시 세션 상한. 전역 임계점과 직접 정합하나
  코드 변경 필요 + 이번 주 Must 범위 확대.
- **(다) 측정으로 판정** — **L5를 먼저 돌려 어느 자원이 먼저 포화하는지(단일 스케줄 락 vs 전역 DB풀/
  커넥션/CPU) 관측한 뒤** 단위를 정한다. ← **채택**.

L5는 이미 50스케줄에 분산하므로, 임계점에서 **병목이 스케줄-지역(개별 avail 락·좌석)인지 전역
공유자원(DB풀·Redis·CPU)인지**를 Prometheus(hikaricp pending/active, CPU, lettuce 지연)로 함께
읽어 판정한다. 전역 자원이 먼저 포화하면 (나) 전역 K가 정합, 스케줄-지역이면 (가) 유지가 타당.

## 5. 환경 타당성 (측정 신뢰성)

- **컨테이너 k6 필수**(`Run-Scenario-Container.ps1`) — 4,000 TPS 에선 Windows/Docker NAT·ephemeral
  포트 한계가 앱보다 먼저 닿을 수 있다(§`K6_Port_Exhaustion_Troubleshooting.md`). 컨테이너↔컨테이너로
  NAT 우회.
- **dropped/refused 의 출처 구분** — 달성 TPS 한계에서 `http_req_failed` 안의 연결오류(status 0)와
  LG(부하생성기) CPU·메모리를 함께 확인. "서버 한계 vs 생성기/네트워크 한계"를 혼동하면 임계점을
  과소평가한다.
- **admission 우회 금지** — L5는 K를 **발동**시켜야 백프레셔를 관측하므로 `-AdmissionMax` 를 운영값
  (현재 100 또는 sweep 대상값)으로 준다. (L1/L2 의 2000 우회와 반대.)
- **WSL VM 자원이 임계점보다 먼저 닿으면 측정 무효**(2026-07-09 1차 시도 실측 교훈). `.wslconfig`
  가 VM 을 **4GB/2vCPU** 로 묶어놨는데 k6+앱+MySQL+Redis+Prometheus+Grafana 가 그 한 VM 에
  공존한다. 4k TPS 단계에서 k6 VU 급증 → VM 메모리+스왑 100% 소진 → **dockerd 가 OOM 으로
  응답 불능**(호스트는 18GB 여유였는데도 VM 한도가 병목). 임계점이 "앱 SLO 한계"가 아니라 "VM OOM
  지점"으로 잡혀 K 역산이 오염된다. 대응:
  - `.wslconfig` **12GB/4vCPU/swap 4GB** 로 상향(호스트 32GB, 20GB 여유). `wsl --shutdown` 후 적용.
  - L5 `maxVUs` 8000→**4000** 하향(필요≈1,900 VU 의 2배). k6 메모리를 절반으로 줄여 VM 압박 완화.
  - **측정 중 `docker logs -f` 상시 스트리밍 금지** — 4k TPS 에선 앱 로그가 초당 수천 줄이라
    스트리밍 자체가 부담. 관측은 Prometheus/Grafana 로. (1차 다운 때 로그에 보인 stats 응답
    지연 ms→2s 는 원인이 아니라 VM 메모리 고갈의 *증상*이었다.)

## 6. 실행 계획 (단계)

| # | 단계 | 산출 | 담당 |
|:-:|------|------|------|
| 1 | 본 플랜 승인 | — | 사용자 |
| 2 | L5 시계열 측정 실행 (K=100 기준, 필요 시 K sweep) | `L5_container_run_*.txt`·`L5_dashboard.html`·`L5_summary.json` | **사용자** |
| 3 | 임계점 판정 + 병목 단위 관측(§3.1·§4) | 안전 TPS·병목 자원 | 공동 해석 |
| 4 | Little's law 역산 + 마진 → K 확정(§3.2·3.3) + 단위 결정(§4) | K 값 + 근거 | 공동 |
| 5 | `application.yml max-active` + `AdmissionProperties` 주석 반영 | 커밋 | 나 |
| 6 | `P4_Result.md` T4-7 섹션 + 체크리스트 `[x]` + 요약표 갱신 | 문서 | 나 |

### 측정 명령 (사용자용)
```powershell
# K=100(운영 잠정값)으로 L5 임계점 시계열 측정. 회차 1회로 충분(계단 전 구간을 시계열로 읽음).
pwsh load-tests/scripts/Run-Scenario-Container.ps1 `
  -Scenario load-tests/scenarios/L5_stress.js -Iterations 1 -AdmissionMax 100 -Dashboard
# → load-tests/results/L5_dashboard.html 을 브라우저로 열어 plateau별 TPS·reserve p99·backpressure·dropped 판독.
# 필요 시 K sweep: -AdmissionMax 50/100/200 각각 돌려 K에 따른 백프레셔·p99 변화를 대조.
```

## 7. 테스트 유효성

- **K 확정은 설정값 변경**이라 단위 테스트 대상 아님 — 검증은 L5 측정 자체가 한다.
- **AdmissionService 상한 로직**(INCR 판정→초과 시 DECR 롤백)은 이미 `AdmissionServiceTest` 가 커버.
  K 값만 바뀌므로 신규 테스트 불필요(테스트 규칙 §계획 단계 유효성 검토 — 프레임워크/설정값은 대상 아님).
- 단위를 (나) 전역 K로 바꾸는 경우에만 `active:global` 로직에 대한 테스트를 추가(그 경우 별도 계획).

## 8. 측정 재정향 — 예매 경로 격리(2026-07-09, ★현재 진행 지점)

**1·2차 L5 측정에서 배운 것 → K 측정을 예매 경로만으로 격리하기로 결정.** 아래가 다음 세션 이어받을 정본.

### 8.1 1·2차 측정 결과와 해석

두 번의 L5(계단 500→1k→2k→4k, `-AdmissionMax 100`)를 돌렸다:

| 회차 | 구성 | 달성 처리량(포화) | reserve p95 | 병목 |
|:-:|------|------|------|------|
| 1차 | 캐시 off·전 최적화 off·pool10 | ~585 req/s | ~5.8s | HikariCP pool 10/10, pending 190 |
| 2차 | **캐시 on·tx밖 on**·pool10 | ~674 req/s | ~5.8s | **여전히 pool 10/10, pending 190** |

- **캐시는 정상 작동**했다(서버측 list mean=72ms, 226k 요청인데도 낮음 = 캐시 히트). list 는 DB 를 거의 안 쳤다.
- 그런데도 pool 이 포화한 건 **병목이 조회가 아니라 예매 write 경로**이기 때문. 서버측 mean: `POST /api/reservations`=593ms, `DELETE /api/reservations/{id}`=605ms (각 DB write, 커넥션 ~0.6s 점유). `POST /api/entry`=1ms(Redis 전용, DB 무관).
- **L5 churn 모델이 write 지배적**: 예매 성공마다 reserve(write)+cancel(write) = **세션당 DB write 2회**. 이게 pool10·2코어 MySQL(T4-5 ① 규명: pool 올려도 ~960 TPS 점근)을 포화시킨 주범.
- **CPU 여유**(app 21%, system 47%) → CPU 병목 아님. **전역 공유자원(DB write/pool)이 스케줄-지역 락보다 먼저 포화** → §4 단위 판정은 **(나) 전역 K** 방향을 시사.

### 8.2 왜 격리하는가 — admission 경계 규명

코드 확인 결과 **입장 제어(K)는 예매 경로만 게이트한다**:
- 게이트 진입 = `POST /api/entry`(→ `AdmissionService.tryEnter`, `active:{scheduleId}` INCR) **한 곳뿐**.
- 토큰 필요(경계 안): `/api/entry`·`POST /api/reservations`·`DELETE /api/reservations/{id}`.
- **경계 밖(게이트 없음): `GET /api/schedules`(리스트)** — 토큰·admission 호출 없음. 설계상 display tier(항상 열림, 캐시로 보호).

→ **L5 의 60% list 트래픽은 K 와 무관**(ungated). K 는 `K ≈ safe_booking_TPS × 세션보유시간` 으로 예매 경로 수명만으로 역산해야 한다. 그래서 list 노이즈를 빼고 예매 경로만 stress 하는 게 정확하다.

- 참고 결정: **list 상한을 위한 "두 번째 K"는 만들지 않는다.** list 는 stateless(세션 없음)라 세션 카운터가 안 맞고, 필요하면 다른 기전(rate limiter)이며, 잠긴 "display 항상 열림" 결정과 충돌. list 포화점은 별도 **용량 수치**로만 기록(E3/L3 데이터 활용, 예매 임계점보다 높음을 보여 "왜 예매만 게이트하나"를 논증).

### 8.3 다음 작업 — `L5b_booking_breakpoint.js` (신규 시나리오)

**계획 승인 완료(2026-07-09). 구현 대기.**

- **신규 파일**로 만든다(L5 는 혼합 현실 stress 로 유지 — 파괴 금지, 서로 다른 질문).
- 루프 = `entry → bookAuto → sleep(HOLD) → cancel`. **list 분기(roll<0.6) 제거**. 매 iteration 이 진짜 예매 세션.
- churn 유지(cancel 로 재고 재순환, 매진 회피) + 50스케줄 분산.
- `HOLD_SECONDS` 를 **env(`__ENV.HOLD_SECONDS`)** 로 — Little's law 의 W(세션 보유시간) 민감도 측정용(운영 가정).
- **`session_duration` Trend 메트릭 추가** — entry 시작~cancel 완료를 측정해 W 를 *추정 아닌 실측*으로 확보(L=λW 의 W).
- 램프(500→1k→2k→4k)·`-AdmissionMax 100`·캐시 on 유지(list 거의 없어 무해, 구성 일관).
- consistency audit(teardown) 유지.

**측정 명령(사용자용):**
```powershell
pwsh load-tests/scripts/Run-Scenario-Container.ps1 `
  -Scenario load-tests/scenarios/L5b_booking_breakpoint.js -Iterations 1 -AdmissionMax 100 -CacheEnabled true -Dashboard
```

**해석·역산:** plateau별 달성 booking-TPS + HikariCP + 실측 `session_duration` → 임계점 판정 → `K ≈ safe_booking_TPS × session_hold` × 마진(0.7~0.8). K 는 현재 구성(캐시 on·pool10·2코어 MySQL)의 write 용량에 묶임을 명시(MySQL 코어/pool 변경 시 K 재역산).

## 9. 결론 — K=100(스케줄당) 확정 (2026-07-10)

L5b(예매경로 격리, 계단 50→100→150→200→300 TPS, `-AdmissionMax 100`·캐시 on) 2회차 측정으로 확정.

### 9.1 임계점 (plateau별 달성 처리량)

progress 시계열의 complete 델타로 plateau별 달성 booking-TPS 를 끊어 읽었다:

| 목표 TPS | 유지 VU | 달성 iter/s | 판정 |
|:-:|:-:|:-:|------|
| 50  | ~43  | ~50  | ✅ 완결 추종 |
| 100 | ~88  | ~100 | ✅ 완결 추종 |
| 150 | ~135 | ~150 | ✅ 완결 추종 |
| 200 | 급증→2000 | ~183 | ⚠️ 목표 미달·VU 폭증 = **임계점** |
| 300 | 2000 고정 | ~189 | ❌ 완전 포화(서버 천장 ~189) |

→ **safe_booking_TPS ≈ 150**(마지막으로 목표를 완결 추종한 plateau). 서버 천장 ~189 는 붕괴점이라 안전값은 그 아래.

### 9.2 W 실측 (Little's law)

안전구간 세 plateau 모두 **W = L/λ = VU/달성TPS ≈ 0.86~0.90s 로 일관**(43/50, 88/100, 135/150).
집계 `session_duration` 5.5s 는 붕괴구간(200~300) 오염값이라 채택하지 않는다. hold=1s 보다 W 가 짧은 건
backpressure(19%)로 막힌 세션이 hold 없이 즉시 끝나 평균 점유가 hold 미만이 되기 때문.

### 9.3 K 역산 + 단위

```
K ≈ safe_TPS × W × 마진 ≈ 150 × 0.9 × 0.75 ≈ 100  (전역 상한 기준)
```

- **잠정값 K=100 과 수렴** — 측정이 사후 검증. 값 변경 없이 근거만 확정.
- **단위 결정(§4) = (가) 스케줄당 K 유지**(사용자 결정 2026-07-10). 병목은 전역(DB write pool)이라
  (나) 전역 K 가 더 정합하나 코드 변경(active:global)+범위 확대라 이번 범위 밖. 전역 100 을 스케줄당
  상한으로 두는 건 "동시 인기 스케줄 1개" 가정의 보수값 — 다수 인기 노선 동시 시 전역 보호가 100×N 으로
  약해지는 한계를 명시. **전역 K 는 후속 과제**(포트폴리오 C6: 측정으로 단위 불일치 규명 + 트레이드오프 기록).

### 9.4 정합성 게이트

teardown audit 은 부하 직후 backlog 로 -1(호출 실패)이었으나, reconcile/sweep 수렴 후 수동 audit
`{availDrift:0, expiredHeld:0, statusViolation:0}` = **오버셀 0 확인**(M2 게이트 green). teardown 90s 대기가
붕괴 backlog 를 다 못 비운 타이밍 문제였음(위반 아님). 부수: `checkConsistency` 를 status 200+body null
케이스에도 -1(위반) 반환하도록 방어 강화 → 거짓 통과 차단(L5/L2b 공유 helper).

## 진행 로그

- 2026-07-09: 플랜 작성. 측정 인프라 재사용 확인(L5_stress.js·Run-Scenario-Container.ps1 기구축).
  K 단위 결정은 측정 후로 보류(사용자 지시). 측정은 사용자 실행, 준비·해석·반영은 내가 담당.
- 2026-07-09: L5 1차 시도 중 **Docker 데몬 다운**. 원인 규명 = `.wslconfig` 4GB/2vCPU 한도에
  전체 스택+k6 가 몰려 4k TPS 단계에서 VM OOM(스왑 100% 소진, dockerd 응답 불능). 호스트는
  18GB 여유였으나 VM 한도가 병목. 대응: `.wslconfig`→12GB/4vCPU/swap4GB, L5 maxVUs 8000→4000
  (§5 반영). 재측정 전 `wsl --shutdown` 으로 .wslconfig 적용 필요.
- 2026-07-09: L5 1·2차 완주(캐시 off/on). **병목 = 예매 write 경로(reserve/cancel ~0.6s)**, list 아님
  (캐시 정상, list 72ms). admission 경계가 예매 경로만 게이트임을 코드로 확인 → **K 측정을 예매 경로만으로
  격리하기로 결정**(§8). 다음: `L5b_booking_breakpoint.js` 신규 작성(계획 승인됨, 구현 대기).
- 2026-07-10: `L5b_booking_breakpoint.js` 작성(list 분기 제거·`SEAT_HOLD_SECONDS` env·`session_duration`
  Trend)+러너 `-SeatHoldSeconds` 배선. **L5b 1차(계단 500→4k) 무효** — reserve 가 500 부터 12s → VU 6000
  즉시 소진, 완료 처리량 ~190/s 평탄 = 서버 write 천장. 계단이 붕괴점보다 위라 해상도 0. 대응: **계단
  저구간(50→300) 재설계 + maxVUs 6000→2000**. 부수: teardown audit 이 붕괴 backlog 로 body null 예외 →
  `consistency_violation=0` 거짓 통과 발견 → `checkConsistency` 방어(§9.4).
- 2026-07-10: **L5b 2차(저구간)로 임계점 확정 → K=100(스케줄당) 확정**(§9). safe_TPS≈150·W≈0.9s·마진0.75.
  정합성 수동 audit 0(오버셀 0). `application.yml`·`AdmissionProperties` 근거 주석 갱신. 전역 K 후속 과제. **T4-7 완료.**
