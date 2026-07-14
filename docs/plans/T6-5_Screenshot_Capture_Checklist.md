# T6-4/5 스크린샷 캡처 체크리스트 (동작 증거 — 영상 대체)

> 상위 문서: `KTX_Ticketing_Task_Checklist.md`(T6-4·T6-5), `plans/Weekly_Plan_W7_2026-07-13.md`
> 출처: `docs/results/P4_Result.md`(T4-3 L1), `load-tests/README.md`, `load-tests/CLAUDE.md`
> 목적: oversell=0(S4·M2)을 "주장→바로 옆 증거"의 **정지 이미지 서사**로 확보한다. 영상 대신
> 스크린샷을 택한 이유·근거는 상위 체크리스트 T6-4/5 항목 참조. 신규 측정이 아니라 **이미 3회
> 재현된 L1 결과를 1회 깨끗하게 캡처**하는 작업.

---

## 캡처 목록 (README 임베드 순서)

| # | 캡처 대상 | 증명 | 필수도 |
|---|-----------|------|:------:|
| **S1** | `SCARD avail:1` = **1000** (부하 전) | 깨끗한 출발선 = 1,000석 선점 풀 준비 완료 | Must |
| **S2** | k6 summary — `reserve_ok:1` · `http_req_failed<0.01` · `consistency_violation:0` · `http_reqs:2000` | 성공 정확히 1건 + 진짜 1,000 동시 + 5xx 누수 0 + 정합성 audit 통과 | Must |
| **S3** | `post_run_check` — ① HELD **1**(그 외 0) · ② 중복 **0행** · ④ AVAILABLE **999** · ⑤ 만료잔재 **0** | DB(SoT)에서 oversell=0·중복=0 | Must |
| **S4** | `SCARD avail:1` = **999** (부하 후) | Redis-DB 드리프트 0 (1000→999 = 정확히 1명만 승리) | Must |
| **S5** | Grafana — 부하 구간 HTTP 요청 스파이크 + Redis SREM 지연 | 실제 1,000 요청이 서버를 때림(부하 실재의 물증) | **권장**(여유 시) |
| **S6** | E1·E2·E3 Before/After 그래프 | 실험 딜리버러블 | Must(**기확보**) |
| **S7** | Swagger UI — 예매(SEAT/AUTO)→확정→취소 호출·응답 + 매진(410) | 정상 흐름 = "직접 확인 가능한 형태"(C3/④) = **T6-4** | Must |

- **S6 는 이미 존재** — `docs/results/P4_Result.md` §T4-9 및 README 임베드 SVG. 새로 안 찍어도 됨.
- **S1~S5 는 L1 을 냉간 1회(컨테이너 k6) 돌리며 캡처.** S1 이 러너 자동화와 충돌하므로 아래 절차는
  **러너에 전부 맡기지 않고 reset 을 수동 분리**한다(§핵심 함정 참조).

---

## ⚠️ 핵심 함정 (읽고 시작)

1. **S1(부하 전 SCARD=1000)은 러너의 "health 완료 ~ k6 발사" 틈에서만 잡힌다.**
   `Run-Scenario-Container.ps1` 은 매 회차 앞에서 `Reset-SeedState`(TRUNCATE+FLUSHDB)를 **강제로**
   하고(=`-SkipReset` 옵션 없음) 곧장 k6 를 쏜다. 따라서 "먼저 수동 reset 하고 러너 실행"은 소용없다
   — 러너가 reset 을 다시 해 좌석이 초기화되기 때문. **S1 은 러너 콘솔에 `app healthy...` 초록 로그가
   뜬 직후(k6 컨테이너 run 시작 전) 별도 터미널에서 SCARD 를 쳐서 잡는다.** 두 터미널 준비 필수.
   (러너 없이 잡고 싶으면 §대안 A 의 수동 루프를 쓴다 — 한 흐름에서 S1→S4 순차 확보.)
2. **컨테이너 k6 로만 신뢰성 확보.** 호스트 k6 는 Windows NAT/TIME_WAIT 로 refused 오염 → `http_reqs`
   가 2000 미만이 되어 "진짜 1,000 동시" 전제가 깨진다(`P4_Result.md` §T4-3 부수 발견).
3. **냉간 1회차만 대표값.** 좌석이 이미 HELD 면 2회차부턴 전부 SeatTaken. 반드시 reset 직후 1회.
4. **S5(Grafana) 원하면 monitoring 을 부하 *전에* 먼저 띄운다.** 나중에 띄우면 스크레이프 공백으로
   빈 그래프만 남는다(`load-tests/CLAUDE.md`).

---

## 캡처 순서 (PowerShell 7, repo 루트에서)

### [0] (S5 원할 때만) monitoring 스택 먼저 기동
```powershell
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up -d prometheus grafana
```
- [ ] Grafana(http://localhost:3000, admin/admin) 접속 확인. 부하 후 시간창을 "Last 5 minutes"로 좁힐 것.

> 아래는 **경로 A(러너 + 두 터미널)** 기준. 두 터미널 조율이 번거로우면 **§대안 B(수동 루프)** 로
> S1→S2→S3→S4 를 한 흐름에서 순차 확보할 수 있다(권장 — 타이밍 압박 없음).

### [1] 터미널 준비 (경로 A)
- **터미널 T1** = 러너 실행용. **터미널 T2** = SCARD 캡처용(대기).
- T2 에 SCARD 명령을 미리 타이핑해 두고 **엔터만 남긴 상태**로 대기(러너 로그가 초록 뜨는 순간 즉시 실행):
  ```powershell
  docker compose exec -T redis redis-cli SCARD avail:1
  ```

### [2] L1 발사 (T1) → S1·S2 캡처
```powershell
# T1: 러너가 reset+restart+health+k6+정합성 을 1회 흐름으로 처리. 냉간 1회차만 대표값이라 -Iterations 1.
pwsh load-tests/scripts/Run-Scenario-Container.ps1 `
  -Scenario load-tests/scenarios/L1_single_seat_race.js -PostRunCheck -Iterations 1
```
- [ ] **S1 캡처(T2)**: T1 콘솔에 `app healthy, admission=2000 ...` **초록 로그**가 뜨는 순간(=k6 run
  직전) T2 에서 엔터 → 출력 **1000**. 이게 "부하 전 1,000석 선점 풀"의 증거. 이 창을 놓치면 §대안 B 로.
- [ ] **S2 캡처(T1)**: k6 종료 후 summary 표. 아래 4줄이 한 화면에 보이게(스크롤 조정):
  - `reserve_ok..........: count==1` ✓ (threshold PASS 초록)
  - `http_req_failed.....: rate<0.01` ✓ (999 전원 깨끗한 409, 5xx 0)
  - `consistency_violation: count==0` ✓ (teardown audit 통과)
  - `http_reqs...........: 2000` (진짜 1,000 동시 경쟁 — refused=0)
- [ ] `http_reqs` 가 **2000 미만이면 refused 오염** → 컨테이너 k6 인지·NAT 우회 재확인 후 재측정.

### [3] 정합성 audit → S3(DB) + S4(부하 후 SCARD=999) 캡처
`-PostRunCheck` 를 줬으면 러너가 이미 아래를 출력했다. 별도로 다시 보려면:
```powershell
pwsh load-tests/scripts/Invoke-PostRunCheck.ps1 `
  -OutputPath load-tests/results/L1_capture_check.txt
```
- [ ] **S3 캡처**: DB 정합성 블록 —
  - `① HELD = 1` (그 외 status 0행)
  - `② 중복 HELD/CONFIRMED = 0행`
  - `④ AVAILABLE = 999`
  - `⑤ 만료 잔재 = 0`
- [ ] **S4 캡처**: `SCARD avail:1 = 999`. (S1 의 1000 과 나란히 배치해 "1 감소" 서사를 만든다.)

### [4] (S5 원할 때만) Grafana 캡처
- [ ] Grafana 대시보드에서 방금 부하 구간을 시간창 "Last 5 minutes"로 조정.
- [ ] **S5 캡처**: `http_server_requests` 요청률 스파이크 + `lettuce_command_completion_seconds`
  (SREM/SPOP·SCARD 지연) 패널. 한 화면에 담기 어려우면 2장으로 분리.

### [5] S7 — Swagger UI 정상 흐름 (T6-4)
> 별도 부하 불필요. app 만 떠 있으면 됨(위 [1] 이후 상태 재사용 가능하나, 좌석 소비를 피하려면
> 새 스케줄/좌석 대상으로 하거나 캡처 후 reset).
```powershell
# 브라우저에서 열기
start http://localhost:8080/swagger-ui.html
```
- [ ] **S7-a**: 엔드포인트 그룹 개요(조회·입장·예매·확정·취소 + `X-Entry-Token` Authorize 자물쇠).
- [ ] **S7-b**: 예매(SEAT) 201 응답 본문. AUTO 모드 201 도 별도 1장.
- [ ] **S7-c**: 매진 상황 410 응답(선택) + 취소 후 좌석 복구 응답 — 상태 전이(HELD→취소→AVAILABLE) 가시화.

---

## 마무리

- [ ] 캡처 파일을 `docs/images/evidence/` (신규) 에 `S1_scard_before.png` … 규칙으로 저장.
- [ ] README 동작 증거 절에 S1→S2→S3→S4 순서로 임베드 + 각 이미지에 **한 줄 캡션**(주장). 예:
  "부하 전 1,000석 → 1,000명 동시 → 성공 1건 → 부하 후 999석 = oversell 0".
- [ ] S6(E1/E2/E3) 는 기존 임베드 재사용(재캡처 불필요).
- [ ] (S5 캡처 시) monitoring 스택 정리: `docker compose -f docker-compose.yml -f docker-compose.monitoring.yml down`
- [ ] 체크리스트 T6-4·T6-5 를 `[x]` 로 갱신(주간계획 진행 로그 동반).

> **재현 실패 시**: `http_reqs<2000`(NAT 오염)·`reserve_ok≠1`(시드 만료=B-4 회귀 또는 좌석 이미 HELD)
> 이면 reset 부터 다시. B-4(시드 상대날짜) 는 해소됐으므로 시드창이 오늘+1일인지만 확인.

---

## 대안 B — 수동 루프 (S1 타이밍 압박 없이 순차 확보)

경로 A 의 "health 초록 순간에 T2 엔터"가 부담이면, 러너 대신 각 단계를 손으로 끊어 실행한다.
러너가 자동화하던 걸 풀어쓴 것이라 **S1 을 여유롭게 찍을 수 있다**(reset 후 k6 를 내가 언제 쏠지
정하므로). 컨테이너 k6 실행만 러너의 방식(same-network, NAT 우회)을 그대로 쓴다.

```powershell
# ── B-1. reset + 재시드 + health 대기 (avail 워밍업 포함) ──
pwsh load-tests/scripts/Reset-Seed.ps1

# ── B-2. S1: 부하 전 SCARD = 1000 ── (여유롭게 캡처)
docker compose exec -T redis redis-cli SCARD avail:1     # → 1000
```
- [ ] **S1 캡처**: 위 출력 **1000**.

```powershell
# ── B-3. L1 발사 (컨테이너 k6, admission 우회 2000, NAT 우회) ──
$env:BOOKING_ADMISSION_MAX_ACTIVE = '2000'
docker compose -f docker-compose.yml -f docker-compose.k6.yml run --rm `
  -e SCHEDULE_ID=1 -e SEAT_INVENTORY_ID=1 `
  k6 run /work/load-tests/scenarios/L1_single_seat_race.js
```
- [ ] **S2 캡처**: k6 summary — `reserve_ok:1` · `http_req_failed<0.01` · `consistency_violation:0` · `http_reqs:2000`.

```powershell
# ── B-4. S3(DB) + S4(부하 후 SCARD=999) ──
pwsh load-tests/scripts/Invoke-PostRunCheck.ps1
```
- [ ] **S3 캡처**: ① HELD=1 · ② 중복 0행 · ④ AVAILABLE=999 · ⑤ 만료잔재 0.
- [ ] **S4 캡처**: `SCARD avail:1 = 999`.

> B-3 의 admission 우회는 러너가 하던 것과 동일(base compose 의 `${BOOKING_ADMISSION_MAX_ACTIVE:-100}`
> 를 env 로 2000 주입). **주의**: 이 수동 경로는 러너의 `Restart-App` env 재주입을 안 거치므로,
> reset(B-1) 이후 app 이 운영 K(100)로 떠 있으면 1,000 VU 중 ~900 이 429 차단된다. 확실히 하려면
> B-1 전에 `./load-tests/scripts/Set-AdmissionOverride.ps1 -On` 으로 app 을 max-active=2000 으로
> 재기동하고, 캡처 후 `-Off` 로 복원한다.
