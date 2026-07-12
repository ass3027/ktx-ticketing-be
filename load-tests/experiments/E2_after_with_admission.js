/**
 * E2 After — 입장 제어 on(K=100): 초과분을 429 로 흡수 → 예매 경로 보전(5xx<1%·p95≤500)
 *
 * 앱 기본(booking.admission.max-active=100, 러너 -AdmissionMax 100)으로 실행.
 * E2_before 와 동일 부하(open-loop, 동일 RATE)·동일 churn 으로 대조한다.
 *
 * 기대: K 처리량 초과분이 입장 단계에서 429 로 흘려져(admission_reject_rate>0.5) 예매 write 경로는 안전
 *       구간에 머문다 → server_errors<10 · reserve p95≤500 · 정합성 0. Before 의 포화가 사라지는 것이 효과.
 * (실질적으로 L4 시나리오와 동형 — E2 는 이를 Before/After 대조 산출물로 승격.)
 */
import { check, sleep } from 'k6';
import http from 'k6/http';
import { Counter, Rate } from 'k6/metrics';
import { textSummary } from '../common/k6-summary.js';
import { SCHEDULE_ID } from '../common/config.js';
import { userIds, getEntryToken, bookAuto, cancelReservation, checkConsistency } from '../common/helpers.js';

http.setResponseCallback(http.expectedStatuses(200, 201, 204, 409, 410, 429));

const admissionRejected = new Counter('admission_rejected');
const admissionRejectRate = new Rate('admission_reject_rate');
const serverErrors = new Counter('server_errors');
const consistencyViolation = new Counter('consistency_violation');

const RATE = parseInt(__ENV.RATE || '500');

export const options = {
    scenarios: {
        E2_after: {
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
    thresholds: {
        admission_reject_rate: ['rate>0.5'],           // 초과가 실제로 429 로 흡수됐는가(핵심 단언)
        'http_req_failed{type:entry}': ['rate<0.01'],  // 입장 경로 진짜 5xx 게이트
        server_errors: ['count<10'],                   // 예매 경로 5xx
        'http_req_duration{type:reserve}': ['p(95)<500'],
        dropped_iterations: ['count==0'],              // 도착률 달성 전제
        consistency_violation: ['count==0'],
    },
};

export default function () {
    const userId = userIds[(__VU - 1) % userIds.length];

    const token = getEntryToken(userId, SCHEDULE_ID);
    if (!token) {
        admissionRejected.add(1);
        admissionRejectRate.add(true); // 입장 거부(429) — 정상 흡수
        return;
    }
    admissionRejectRate.add(false);

    const res = bookAuto(token);
    if (res.status >= 500) {
        serverErrors.add(1);
        check(res, { 'no 5xx': () => false });
        return;
    }
    check(res, { 'reserve ok': (r) => [201, 409, 410].includes(r.status) });

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
        console.error(`[E2-after] CONSISTENCY VIOLATION: ${violations}`);
    }
}

export function handleSummary(data) {
    const rej = data.metrics['admission_rejected'] ? data.metrics['admission_rejected'].values['count'] : 0;
    const errs = data.metrics['server_errors'] ? data.metrics['server_errors'].values['count'] : 0;
    console.log(`[E2 After] admission_rejected(429)=${rej} (초과 흡수), server_errors=${errs} (<10 기대)`);
    const prefix = __ENV.RESULT_PREFIX || 'E2_after';
    return {
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
        [`load-tests/results/${prefix}_summary.json`]: JSON.stringify(data, null, 2),
    };
}
