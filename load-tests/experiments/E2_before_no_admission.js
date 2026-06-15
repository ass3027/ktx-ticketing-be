/**
 * E2 Before: 입장 제어 없음 → 5xx 폭증 확인
 *
 * 전제: 앱을 booking.admission.max-active=999999 로 기동 (제어 사실상 비활성)
 *   -Dbooking.admission.max-active=999999
 *
 * 목적: 입장 제어 없이 대규모 동시 요청 → 5xx 율 기록
 * E2_after 와 동일 부하로 Before/After 비교
 */
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { SCHEDULE_ID } from '../common/config.js';
import { userIds, getEntryToken, bookAuto } from '../common/helpers.js';

const serverErrors = new Counter('server_errors');

export const options = {
    scenarios: {
        E2_before: {
            executor: 'ramping-vus',
            stages: [
                { duration: '30s', target: 500 },
                { duration: '3m',  target: 500 },
                { duration: '30s', target: 0   },
            ],
        },
    },
    // 임계값 느슨 — 5xx 폭증을 관측하는 것이 목적
    thresholds: {
        'http_req_failed': ['rate<0.99'],
    },
};

export default function () {
    const userId = userIds[(__VU - 1) % userIds.length];

    const token = getEntryToken(userId, SCHEDULE_ID);
    if (!token) { sleep(0.5); return; }

    const res = bookAuto(token);

    if (res.status >= 500) {
        serverErrors.add(1);
        check(res, { 'E2-before 5xx (기록용)': () => true });
    }

    sleep(0.3);
}

export function handleSummary(data) {
    const errs = data.metrics['server_errors'] ? data.metrics['server_errors'].values['count'] : 0;
    console.log(`[E2 Before] server_errors=${errs} (입장 제어 없음 → 5xx 폭증 예상)`);
    return {};
}
