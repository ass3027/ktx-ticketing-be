# KTX 예매 시스템 — 부하 테스트 실행 단축 커맨드
# 전제: k6 로컬 설치, Docker Compose 앱 기동 중
# k6 내장 대시보드: http://localhost:5665 (K6_WEB_DASHBOARD=true)

.PHONY: reset-seed run-L1 run-L2 run-L2b run-L3 run-L4 run-L5 run-L6 \
        run-E1-before run-E1-after run-E2-before run-E2-after run-E3-before run-E3-after

reset-seed:
	bash load-tests/seed/reset.sh

# ── 시나리오 ──────────────────────────────────────────────────────────────────

run-L1:
	K6_WEB_DASHBOARD=true k6 run load-tests/scenarios/L1_single_seat_race.js

run-L2:
	K6_WEB_DASHBOARD=true k6 run load-tests/scenarios/L2_normal_flow.js

run-L2b:
	K6_WEB_DASHBOARD=true k6 run load-tests/scenarios/L2b_auto_assign.js

run-L3:
	K6_WEB_DASHBOARD=true k6 run load-tests/scenarios/L3_list_query.js

run-L4:
	K6_WEB_DASHBOARD=true k6 run load-tests/scenarios/L4_admission_overload.js

run-L5:
	K6_WEB_DASHBOARD=true k6 run load-tests/scenarios/L5_stress.js

run-L6:
	K6_WEB_DASHBOARD=true k6 run load-tests/scenarios/L6_soak.js

# ── 실험 (Before / After) ─────────────────────────────────────────────────────

# E1: 선점 제어 off → oversell 발생 확인
# 앱 기동 시 booking.preemption.enabled=false 환경변수 필요 (T4-9 참조)
run-E1-before:
	K6_WEB_DASHBOARD=true k6 run load-tests/experiments/E1_before_no_preemption.js

# E1: 선점 제어 on (기본값) → oversell == 0
run-E1-after:
	K6_WEB_DASHBOARD=true k6 run load-tests/experiments/E1_after_with_preemption.js

# E2: 입장 제어 off → 5xx 폭증
# 앱 기동 시 booking.admission.max-active=999999 환경변수로 제어 비활성화
run-E2-before:
	K6_WEB_DASHBOARD=true k6 run load-tests/experiments/E2_before_no_admission.js

# E2: 입장 제어 on → 429 흡수, 5xx < 1%
run-E2-after:
	K6_WEB_DASHBOARD=true k6 run load-tests/experiments/E2_after_with_admission.js

# E3: 조회 캐시 off → DB 직접 집계
# 앱 기동 시 booking.query-cache.enabled=false 환경변수 필요 (T4-9 참조)
run-E3-before:
	K6_WEB_DASHBOARD=true k6 run load-tests/experiments/E3_before_no_cache.js

# E3: 조회 캐시 on (기본값, Redis SCARD)
run-E3-after:
	K6_WEB_DASHBOARD=true k6 run load-tests/experiments/E3_after_with_cache.js
