> 상위 문서: `KTX_Ticketing_Development_Plan.md` (★확정 일정 W6)·`KTX_Ticketing_Task_Checklist.md` (P4)
> 출처: 2026-07-06 세션 — W6 주간 계획 수립
> 상태: 확정 (주 15h · P4 핵심 완결 범위)
> 연관: T4-5(`Query_Path_Optimization_Plan.md`)·T4-7(L5→K)·T4-9(E1·E2·E3)·P4_Result.md

# 주간 계획 — W6 (2026-07-06 월 ~ 07-12 일)

## 0. 한 줄 요약

이번 주 = **P4 성능 측정의 Must/Should를 정직하게 닫아 진짜 M4(수치 확보) 달성**.
E1·E2·E3 Before/After + 활성자 상한 K 확정 + 그래프까지 `P4_Result.md`에 확보한다.
P6 산출물(README·그래프)이 이 수치에 의존하므로, 여기서 못 닫으면 W7 제출(M5·07/19)이 흔들린다.

> **✅ T4-9 (E1·E2) 완료(2026-07-12) → 주간 Must 전부 종료, M4 완결.** T4-5 ③④·T4-7 완료. **T4-7 결과(2026-07-10)**: L5b 예매경로
> 격리 측정으로 **K=100(스케줄당) 확정** — safe_TPS≈150 × W≈0.9s × 마진0.75 ≈ 100(잠정값과 수렴).
> 오버셀 0. 전역 K 는 후속(T4-14). 정본: `Admission_K_Calibration_Plan.md` §9 + `P4_Result.md` T4-7.

## 1. 진입 좌표 (2026-07-06 기준)

- 전체 진행률 63% (34/54). P0~P3(M1·M2·M3) 완료.
- **P4 성능 측정 46%**(6/13) — 진행 중. T4-5·T4-7 완료, 남은 Must = T4-9(E1·E2).
- 원 일정상 W6(07/06~12)=P4 마무리 → **M4(07/07)**, W7(07/13~19)=P6 → **M5 제출(07/19)**.
- 결정(2026-07-06): 이번 주 범위 = **P4 핵심만 닫기**(스트레치 실험 제외), 가용 시간 = **주 15h 안팎**.

## 2. 이번 주 작업 (일자 블록 · MoSCoW 우선순위)

| # | 블록 | 작업 | 등급 | 완료 기준 (측정 게이트) |
|:-:|------|------|:---:|------|
| 1 | 월~화 | ✅ **T4-5 ③ pipeline** — 구현+테스트 완료. **측정은 효과크기 논증으로 갈음**: N≈8 운영점에선 ~1.4ms=노이즈 이하 → 레버 아님(N 부풀린 측정=theater 로 기각). 코드는 대용량 페이지 안전용 유지 | Should | ~~A3·C3 측정~~ → 운영 N 에서 효과 바닥 이하 논증(P4_Result.md T4-5 ③) |
| 2 | 수 | **T4-5 ④ 조회 캐시** — **Redis 공유 + single-flight**(`booking.query-cache.enabled`, TTL≤2s) 구현 + A4·C4 측정. 로컬 아닌 공유(멀티 인스턴스 일관, 잠긴 2-tier 정합), stampede 는 `DistributedLock` double-check. 설계 §3.5 | Should | 매진 보수성·오버셀 0 유지. **E3 after 확보**(= T4-9 E3 자동 완료) |
| 3 | 목 | ✅ **T4-7 L5 임계점 → 활성자 상한 K 확정.** 예매 경로 격리 재측정(`L5b_booking_breakpoint.js`)으로 **K=100(스케줄당) 확정**: safe_TPS≈150 × W≈0.9s × 마진0.75 ≈ 100(잠정값과 수렴). 오버셀 0. 전역 K 는 후속(T4-14). 정본: `Admission_K_Calibration_Plan.md` §9 | **Must** | ✅ K 결정값 + 근거 수치, `booking.admission.max-active` 근거 확정 |
| 4 | 금 | ✅ **T4-9 E1·E2 완료** — **E1 재정의**: 선점 off/on 정확성 동일(oversell 0, `@Version` 방어) → 선점의 값은 *DB 부하 회피*(reserve 중앙값 ~4×↓·availDrift 0). 부산물로 OptLock→409 advice 하드닝. **E2**: 제어 off=무한 열화(p95 22s·drop 38k·VU 2000) → on=85.4% 429 흡수·drop 0·reserve p95 ~28ms | **Must** | ✅ 양쪽 정합성 0 + 트레이드오프 실측(P4_Result.md §T4-9) |
| 5 | 버퍼(주말) | `P4_Result.md` 정리 + Grafana 그래프 캡처 + 체크리스트 `[x]` 처리 | — | T4-5·T4-7·T4-9 완료 → **M4 완결** |

> 이번 주 완료 시 P4 = 12개 중 9개(75%). 남는 3개(T4-10/11/12 스트레치 실험)는 W7 이후 버퍼로 이월.

## 3. 순서·의존성 (고정)

- **③④ → K → E1·E2** 순서 고정. T4-7의 K는 입장 제어로 **소급 반영**되므로, K 확정 전 L4/L6 재측정은 무의미.
- **E3는 별도 작업 아님** — T4-5 ④ 조회 캐시 토글이 곧 E3 after (Before는 §1 L3 실측으로 이미 확보).

## 4. 범위 밖 (이번 주 안 함)

- **스트레치 실험** T4-10(E5 가상스레드)·T4-11(E6 락 라이브러리)·T4-12(E7 Memcached) → 제출 상태 확보 후.
- **P5(MQ 비동기)**·**P7(블로그)** → Could/가산 항목, 버퍼 있을 때만.
- **W7(07/13~19) = P6 산출물 전념**(README·다이어그램·배포·영상) → **M5 제출(07/19)**.

## 5. 주간 DoD (Definition of Week-done)

- [x] T4-5 ③ pipeline 결론 (N≈8 에선 레버 아님 — 효과크기 논증으로 측정 갈음)
- [x] T4-5 ④ 조회 캐시 측정 완료 (= E3 after 확보) — A4/C4 단일 핫키 SLO 통과 + 다중 키 한계·jitter 규명
- [x] T4-7 L5 임계점 → K=100(스케줄당) 확정 + `application.yml`·`AdmissionProperties` 근거 반영 (전역 K는 T4-14 후속)
- [x] T4-9 E1·E2 Before/After 실측 완료 (2026-07-12) — E1: 선점 off/on 정확성 동일(oversell 0)·선점 on reserve 중앙값 ~4×↓·availDrift 0 / E2: 제어 off(p95 22s·drop 38k·VU 2000) → on(85.4% 429 흡수·drop 0·reserve p95 ~28ms). E3=T4-5 ④
- [x] `P4_Result.md` 정리 + 체크리스트 T4-5·T4-7·T4-9 `[x]` → **M4 완결**(수치 확보)
