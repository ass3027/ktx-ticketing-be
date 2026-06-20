/**
 * L4: 입장 초과 부하
 *
 * 목적: 활성자 상한 K 초과 시 입장 제어(429) 정상 동작 검증 (S5), 실험 E2 기준선
 * 모델: 열린 루프(ramping-arrival-rate) — K(동시 활성 상한)를 넘기려면 도착률을 고정해 꾸준히
 *       밀어넣어야 한다. 닫힌 루프는 429 로 빠르게 반려된 VU 가 곧장 재시도해 실제 도착률이
 *       응답 속도에 종속된다(초과 부하를 제대로 못 만든다).
 * 프로파일: K 처리량을 초과하는 RATE TPS 로 입장+예매 시도 (기본 500 TPS, K=100 가정)
 * 합격: 초과분은 429 로 흡수(server_errors<10), 코어 예매 경로 p95≤500, dropped_iterations==0
 *
 * 주의: booking.admission.max-active (현재 100) 대비 충분히 높은 RATE 로 설정해야 입장 제어가 발동.
 */
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';
import { SCHEDULE_ID } from '../common/config.js';
import { userIds, getEntryToken, bookAuto } from '../common/helpers.js';

const admissionRejected = new Counter('admission_rejected'); // 429 — 정상
const serverErrors = new Counter('server_errors');           // 5xx — 비정상

const RATE = parseInt(__ENV.RATE || '500');

export const options = {
    scenarios: {
        L4: {
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
        // 5xx 는 1% 미만이어야 함 (429/503 입장 제어는 정상이라 제외)
        server_errors: ['count<10'],
        'http_req_duration{type:reserve}': ['p(95)<500'],
        'dropped_iterations': ['count==0'], // 초과 부하 도착률을 실제로 달성했는가(전제 단언)
    },
};

export default function () {
    const userId = userIds[(__VU - 1) % userIds.length];

    const token = getEntryToken(userId, SCHEDULE_ID);
    if (!token) {
        admissionRejected.add(1);
        return; // 429 — 입장 제어 정상 동작
    }

    const res = bookAuto(token);
    if (res.status >= 500) {
        serverErrors.add(1);
        check(res, { 'no 5xx': () => false });
    } else {
        check(res, { 'reserve ok': (r) => [201, 409, 410].includes(r.status) });
    }
    // 열린 루프: sleep 제거 — 페이싱은 arrival-rate 가 담당.
}

export function handleSummary(data) {
    return {
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
        'load-tests/results/L4_summary.json': JSON.stringify(data, null, 2),
    };
}
