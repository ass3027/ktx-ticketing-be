/**
 * E2 Before — 입장 제어 off: 초과 부하가 그대로 예매 경로로 → 포화(5xx·지연 폭등) 관측
 *
 * 전제: 앱을 booking.admission.max-active=999999 로 기동(러너 -AdmissionMax 999999) → 사실상 입장 제어 없음.
 * 모델: 열린 루프(ramping-arrival-rate, L4 와 동일) — 닫힌 루프는 429/실패가 재시도로 자기조절돼 초과를
 *       못 만든다. 입장 제어가 없으므로 모든 도착이 토큰을 받고 곧장 bookAuto 로 내려가 write 경로가 포화한다.
 *
 * 목적: 입장 제어 없이 K 처리량을 초과하는 도착률을 밀어넣어 server_errors(5xx)·reserve p95 열화를 기록.
 *       E2_after(입장 on)와 동일 부하·지표로 대조 → "초과를 429 로 앞단에서 흘리는" 효과를 수치화한다.
 * 관측: 실패/드롭은 포화의 증거이므로 threshold 로 죽이지 않는다(느슨). 정합성(consistency==0)은 유지돼야 한다.
 */
import { check, sleep } from 'k6';
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { textSummary } from '../common/k6-summary.js';
import { SCHEDULE_ID } from '../common/config.js';
import { userIds, getEntryToken, bookAuto, cancelReservation, checkConsistency } from '../common/helpers.js';

http.setResponseCallback(http.expectedStatuses(200, 201, 204, 409, 410, 429));

const serverErrors = new Counter('server_errors');
const consistencyViolation = new Counter('consistency_violation');

const RATE = parseInt(__ENV.RATE || '500');

export const options = {
    scenarios: {
        E2_before: {
            executor: 'ramping-arrival-rate',
            startRate: 0,
            timeUnit: '1s',
            stages: [
                { duration: '30s', target: RATE },
                { duration: '3m', target: RATE },
                { duration: '30s', target: 0 },
            ],
            preAllocatedVUs: 500,
            maxVUs: 2000,
        },
    },
    // 느슨 — 포화(5xx·drop)를 관측하는 것이 목적. 정합성만 진짜 게이트로 유지.
    thresholds: {
        'http_req_failed': ['rate<0.99'],
        consistency_violation: ['count==0'],
    },
};

export default function () {
    const userId = userIds[(__VU - 1) % userIds.length];

    const token = getEntryToken(userId, SCHEDULE_ID);
    if (!token) return; // 입장 제어 off 라 사실상 발생하지 않음(max-active=999999)

    const res = bookAuto(token);
    if (res.status >= 500) {
        serverErrors.add(1);
        check(res, { 'E2-before 5xx (기록용)': () => true });
        return;
    }

    // 슬롯/좌석 churn — 취소로 재고를 회수해 3분 내내 부하 지속(재고 소진 회피).
    if (res.status === 201) {
        const reservationId = res.json('reservationId');
        sleep(1 + Math.random());
        cancelReservation(token, reservationId);
    }
}

export function teardown() {
    const violations = checkConsistency();
    if (violations !== 0) {
        consistencyViolation.add(violations === -1 ? 1 : violations);
        console.error(`[E2-before] CONSISTENCY VIOLATION: ${violations}`);
    }
}

export function handleSummary(data) {
    const errs = data.metrics['server_errors'] ? data.metrics['server_errors'].values['count'] : 0;
    console.log(`[E2 Before] server_errors=${errs} (입장 제어 없음 → 5xx/지연 폭증 예상)`);
    const prefix = __ENV.RESULT_PREFIX || 'E2_before';
    return {
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
        [`load-tests/results/${prefix}_summary.json`]: JSON.stringify(data, null, 2),
    };
}
