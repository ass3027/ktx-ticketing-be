/**
 * L5b: 예매 경로 격리 임계점 탐색 (Booking Breakpoint) — T4-7 K 역산 전용.
 *
 * L5 는 혼합 현실 stress(60% list + 40% 예매)라, 병목이 조회가 아니라 예매 write 경로임을 규명한 뒤엔
 * K 역산 입력(safe_booking_TPS)에 list 트래픽이 노이즈가 된다. 입장 제어(K)는 `POST /api/entry` 한 곳,
 * 예매 경로만 게이트하므로(`GET /api/schedules` 는 ungated display tier), 여기선 list 를 빼고 매 iteration
 * 을 순수 예매 세션(entry→bookAuto→hold→cancel)으로 돌려 예매 경로만 임계점까지 몬다.
 *
 * <b>W 실측</b> — Little's law `K ≈ λ×W` 의 W(세션 보유시간)를 추정이 아니라 {@link sessionDuration}
 * 으로 실측한다(entry 시작~cancel 완료). 성공 세션(201→cancel)만 집계 — 백프레셔로 막힌 iteration 은
 * 세션이 성립하지 않는다.
 *
 * churn·스케줄 분산·backpressure·정합성 audit·판독법은 L5_stress.js 와 동일하다.
 */
import { check, sleep } from 'k6';
import http from 'k6/http';
import exec from 'k6/execution';
import { Rate, Counter, Trend } from 'k6/metrics';
import { textSummary } from '../common/k6-summary.js';
import { SCHEDULE_COUNT } from '../common/config.js';
import { userIds, getEntryToken, bookAuto, cancelReservation, checkConsistency } from '../common/helpers.js';

// 409 경쟁패배·410 매진·429/503 입장제어 = 설계상 의도된 응답 → 서버오류 아님.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 410, 429, 503));

const backpressure = new Rate('backpressure');            // 예매 경로가 429/503 에 막힌 비율 = K 역산 입력
const consistencyViolation = new Counter('consistency_violation');
const sessionDuration = new Trend('session_duration', true); // Little's law 의 W 실측(entry~cancel, ms)

const seatHoldSeconds = Number(__ENV.SEAT_HOLD_SECONDS) || 1; // 예매 후 좌석 점유 후 취소(churn) — W 민감도 측정용

export const options = {
    discardResponseBodies: true,
    teardownTimeout: '150s',
    scenarios: {
        L5b: {
            executor: 'ramping-arrival-rate',
            startRate: 0,
            timeUnit: '1s',
            // 순수 예매(write 2회/세션)의 현 구성(pool10·2코어 MySQL) 천장은 ~190 booking-TPS(1차 실측:
            // 500 목표에서 이미 붕괴, 완료 처리량 ~190/s 평탄). 500 부터 시작하면 첫 칸부터 붕괴라 해상도 0 →
            // 190 근방을 끼우는 저구간 계단으로 임계점을 해상도 있게 잡는다.
            stages: [
                { duration: '30s', target: 50  },
                { duration: '2m',  target: 50  },
                { duration: '30s', target: 100 },
                { duration: '2m',  target: 100 },
                { duration: '30s', target: 150 },
                { duration: '2m',  target: 150 },
                { duration: '30s', target: 200 },
                { duration: '2m',  target: 200 },
                { duration: '30s', target: 300 },
                { duration: '2m',  target: 300 },
                { duration: '30s', target: 0   },
            ],
            // 안전 구간(≤200 TPS) iteration ≈ reserve(~0.6s)+hold(1s)+cancel(~0.6s) ≈ 2.2s → 200 TPS 엔
            // ~440 VU. 붕괴 구간(300~)은 iteration 이 길어져 상한을 넘으면 dropped 로 임계점이 드러난다.
            preAllocatedVUs: 500,
            maxVUs: 2000,
        },
    },
    thresholds: {
        'http_req_failed': ['rate<0.5'],
        'http_req_duration{type:reserve}': ['p(99)<1000'],
        consistency_violation: ['count==0'],
    },
};

export default function () {
    const i = exec.scenario.iterationInTest;
    const userId = userIds[i % userIds.length];
    const scheduleId = 1 + (i % SCHEDULE_COUNT); // 50개 스케줄에 분산 → 단일 avail 키 매진 회피

    const started = Date.now();
    const token = getEntryToken(userId, scheduleId);
    if (!token) { backpressure.add(true); return; } // 입장 단계 차단(429/503) = 의도된 백프레셔

    const res = bookAuto(token);
    const shed = res.status === 429 || res.status === 503;
    backpressure.add(shed);
    check(res, { 'reserve handled': (r) => [201, 409, 410, 429, 503].includes(r.status) });

    if (res.status === 201) {
        const rid = res.json('reservationId');
        sleep(seatHoldSeconds);                  // 점유 시간 모델 — 페이싱 아님(페이싱은 arrival-rate 담당)
        if (rid) cancelReservation(token, rid);  // churn: 좌석(avail SADD)·슬롯(leave DECR)·토큰 반환
        sessionDuration.add(Date.now() - started); // 성공 세션의 W 실측
    }
}

export function teardown() {
    sleep(90); // reconcile 1주기(60s) + sweep(30s) 수렴 여유
    const violations = checkConsistency();
    if (violations !== 0) {
        consistencyViolation.add(violations === -1 ? 1 : violations);
        console.error(`[L5b] CONSISTENCY VIOLATION: ${violations} (audit /internal/consistency)`);
    }
}

export function handleSummary(data) {
    const prefix = __ENV.RESULT_PREFIX || 'L5b';
    const dropped = data.metrics['dropped_iterations']
        ? data.metrics['dropped_iterations'].values['count'] : 0;
    if (dropped > 0) {
        console.warn(`[${prefix}] dropped_iterations=${dropped} — 이 지점부터 달성 TPS<목표. 서버 한계인지 LG 한계인지 LG 자원과 대조해 임계점 판정.`);
    }
    const bp = data.metrics['backpressure'] ? data.metrics['backpressure'].values['rate'] : 0;
    console.log(`[${prefix}] backpressure(429/503) rate=${(bp * 100).toFixed(1)}% — 예매 경로가 입장 제어에 막힌 비율. K 역산 입력.`);
    const sd = data.metrics['session_duration'] ? data.metrics['session_duration'].values : null;
    if (sd) {
        console.log(`[${prefix}] session_duration(W) avg=${sd['avg'].toFixed(0)}ms p95=${sd['p(95)'].toFixed(0)}ms — Little's law W 실측. K ≈ safe_TPS × W.`);
    }
    return {
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
        [`load-tests/results/${prefix}_summary.json`]: JSON.stringify(data, null, 2),
    };
}
