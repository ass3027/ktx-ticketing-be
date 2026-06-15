/**
 * E2 After: 입장 제어 on (기본값 K=100) → 429 흡수, 5xx < 1%
 *
 * 앱 기본 설정(booking.admission.max-active=100)으로 실행.
 * E2_before 와 동일 부하, 결과를 Before/After 그래프로 비교.
 *
 * 합격: server_errors < 10, admission_rejected(429) 다수 — 정상 흡수
 */
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { SCHEDULE_ID } from '../common/config.js';
import { userIds, getEntryToken, bookAuto } from '../common/helpers.js';

const admissionRejected = new Counter('admission_rejected');
const serverErrors = new Counter('server_errors');

export const options = {
    scenarios: {
        E2_after: {
            executor: 'ramping-vus',
            stages: [
                { duration: '30s', target: 500 },
                { duration: '3m',  target: 500 },
                { duration: '30s', target: 0   },
            ],
        },
    },
    thresholds: {
        server_errors: ['count<10'],
        'http_req_duration{type:reserve}': ['p(95)<500'],
    },
};

export default function () {
    const userId = userIds[(__VU - 1) % userIds.length];

    const token = getEntryToken(userId, SCHEDULE_ID);
    if (!token) {
        admissionRejected.add(1);
        sleep(1);
        return; // 429 — 입장 제어 정상 동작
    }

    const res = bookAuto(token);

    if (res.status >= 500) {
        serverErrors.add(1);
        check(res, { 'no 5xx': () => false });
    } else {
        check(res, { 'E2-after ok': (r) => [201, 409, 410].includes(r.status) });
    }

    sleep(0.5);
}
