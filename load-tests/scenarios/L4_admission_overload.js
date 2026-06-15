/**
 * L4: 입장 초과 부하
 *
 * 목적: 활성자 상한 K 초과 시 입장 제어(429) 정상 동작 검증 (S5), 실험 E2 기준선
 * 프로파일: K 의 5배 VUser 가 입장+예매 시도 (K=100 기본값 → 500 VUser)
 * 합격: 초과분은 429 로 흡수 (5xx < 1%), 코어 예매 경로 p95 유지
 *
 * 주의: booking.admission.max-active (현재 100) 대비 충분히 많은 VUser 로 설정
 */
import { check, sleep } from 'k6';
import { Rate, Counter } from 'k6/metrics';
import { SCHEDULE_ID } from '../common/config.js';
import { userIds, getEntryToken, bookAuto } from '../common/helpers.js';

const admissionRejected = new Counter('admission_rejected'); // 429 — 정상
const serverErrors = new Counter('server_errors');           // 5xx — 비정상

export const options = {
    scenarios: {
        L4: {
            executor: 'ramping-vus',
            stages: [
                { duration: '30s', target: 500 },
                { duration: '3m', target: 500 },
                { duration: '30s', target: 0 },
            ],
        },
    },
    thresholds: {
        // 5xx 는 1% 미만이어야 함 (429/503 입장 제어는 제외)
        server_errors: ['count<10'],
        'http_req_duration{type:reserve}': ['p(95)<500'],
    },
};

export default function () {
    const userId = userIds[(__VU - 1) % userIds.length];

    const entryRes = getEntryToken(userId, SCHEDULE_ID);

    if (!entryRes) {
        admissionRejected.add(1);
        sleep(1);
        return; // 429 — 입장 제어 정상 동작
    }

    const res = bookAuto(entryRes);

    if (res.status >= 500) {
        serverErrors.add(1);
        check(res, { 'no 5xx': () => false });
    } else {
        check(res, { 'reserve ok': (r) => [201, 409, 410].includes(r.status) });
    }

    sleep(0.5);
}
