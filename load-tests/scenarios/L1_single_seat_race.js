/**
 * L1: 직접 선택 — 단일 좌석 동시 경쟁 (정합성)
 *
 * 목적: 1,000 VUser 가 동일 좌석에 동시 SEAT 예매 → 성공 정확히 1건, oversell == 0 (S4)
 * 합격: reserve_ok count == 1 (2건 이상이면 오버셀 → 실패). 최종 판정은 DB(post_run_check.sql).
 *
 * <b>실행 전 준비</b> — `booking.admission.max-active` 가 운영 잠정값(100)이면 1,000 VU 중 ~900
 * 이 입장 단계에서 429 차단돼 좌석 경쟁 자체가 일어나지 않는다. L1 은 *좌석 선점 정합성* 검증이
 * 목적이므로 입장 제어를 우회해야 한다. docker-compose 의 app 서비스 환경변수에 일시 추가:
 *   BOOKING_ADMISSION_MAX_ACTIVE: 2000
 * 그 뒤 `docker compose up -d --force-recreate app` 으로 컨테이너 재기동. L4 측정 시 원복.
 *
 * <b>반복 실행 시</b> — 첫 회는 정상 경쟁(1명 win, 999명 SeatTaken)이지만 좌석이 이미 HELD 라
 * 두 번째 회부턴 전부 SeatTaken 만 나온다. 매 회 측정 전 `make reset-seed && docker compose
 * restart app` 으로 DB/Redis 초기화하고 DataInitializer 재시드해야 한다.
 *
 * 부하 후 DB/Redis 정합성: `load-tests/verify/post_run_check.sql` ①·④번 + redis-cli 안내 참조.
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';
import { SCHEDULE_ID, SEAT_INVENTORY_ID } from '../common/config.js';
import { userIds, getEntryToken, bookSeat, checkConsistency } from '../common/helpers.js';

// 오버셀은 reserve_ok==1 threshold 로 간접 검증 + teardown 의 정합성 audit(U-2)으로 자동 확정.
const reserveOk = new Counter('reserve_ok');
// 부하 후 정합성 위반 합계(availDrift+expiredHeld+statusViolation). teardown 게이트가 채운다.
const consistencyViolation = new Counter('consistency_violation');

// VU 수 = 경쟁자 수. setup 토큰 발급 루프와 executor 가 같은 값을 쓰도록 단일 상수로 묶는다.
const VU_COUNT = 1000;

// 경쟁 패배(409)·매진(410)·입장 제어(429)는 정상 응답 → http_req_failed 에서 제외.
// 이래야 http_req_failed 가 SLO(5xx<1%) 와 같은 의미가 된다(진짜 서버 오류만 집계).
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 410, 429));

export const options = {
    scenarios: {
        L1: {
            executor: 'shared-iterations',
            vus: VU_COUNT,
            iterations: VU_COUNT,
            maxDuration: '2m',
        },
    },
    thresholds: {
        reserve_ok: ['count==1'],
        http_req_failed: ['rate<0.01'], // 999 패배자가 깨끗한 4xx 인지 — 5xx 누수 가드
        consistency_violation: ['count==0'], // 부하 후 Redis-DB 정합성(U-2) — ps1 사후 SQL 대체
    },
};

// 입장 토큰은 경쟁 대상이 아니다. 본문에서 발급하면 토큰 왕복 지연이 VU마다 달라 정작
// 경쟁시킬 bookSeat 가 시간축으로 흩어진다(동시성 희석). 1,000개를 미리 발급해 두고 본문은
// 단일 bookSeat 만 쏴 좌석 선점의 worst-case(최대 동시 도달)를 재현한다.
export function setup() {
    const tokens = [];
    for (let i = 0; i < VU_COUNT; i++) {
        const token = getEntryToken(userIds[i], SCHEDULE_ID);
        // 토큰 실패 = 전제 붕괴(입장 우회 max-active 누락). throw 로 측정 자체를 중단해 침묵 통과 방지.
        if (!token) {
            throw new Error(
                `[L1] setup 입장 토큰 실패 @user=${userIds[i]} (i=${i}) — ` +
                `BOOKING_ADMISSION_MAX_ACTIVE 우회 누락 의심. 측정 무효.`
            );
        }
        tokens.push(token);
    }
    return { tokens };
}

export default function (data) {
    // VU 당 1회(vus==iterations)라 __VU 가 토큰과 1:1. 선행 단계 없이 곧장 단일 경쟁 요청만 쏜다.
    const res = bookSeat(data.tokens[__VU - 1], SEAT_INVENTORY_ID);

    if (check(res, { 'status is 201': (r) => r.status === 201 })) {
        reserveOk.add(1);
    }
}

// 부하 종료 후 1회 정합성 audit → 위반 합계를 Counter 로 승격(threshold count==0 판정).
// audit 은 읽기전용(mutation 없음)이라 측정 결과를 오염시키지 않는다.
export function teardown() {
    const violations = checkConsistency();
    if (violations !== 0) {
        consistencyViolation.add(violations === -1 ? 1 : violations);
        console.error(`[L1] CONSISTENCY VIOLATION: ${violations} (audit /internal/consistency)`);
    }
}

// 성공 2건 이상이면 오버셀 경고. 표준 요약(콘솔 표)+JSON 으로 p95/p99·http_reqs 보존.
export function handleSummary(data) {
    const ok = data.metrics['reserve_ok'] ? data.metrics['reserve_ok'].values['count'] : 0;
    if (ok > 1) {
        console.error(`[L1] OVERSELL DETECTED: reserve_ok=${ok} (expected 1)`);
    }
    return {
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
        'load-tests/results/L1_summary.json': JSON.stringify(data, null, 2),
    };
}
