/**
 * L3: 운행 조회 폭주 (읽기 부하)
 *
 * 목적: 읽기 경로 응답·캐시 효과 측정 (S2), 실험 E3 기준선
 * 프로파일: 0→5,000 VUser ramp-up 1분
 * 합격: 조회 p95 ≤ 200ms
 */
import { check, sleep } from 'k6';
import { DEP, ARR, FROM_DATE } from '../common/config.js';
import { listSchedules } from '../common/helpers.js';

export const options = {
    scenarios: {
        L3: {
            executor: 'ramping-vus',
            stages: [
                { duration: '1m', target: 5000 },
                { duration: '3m', target: 5000 },
                { duration: '30s', target: 0 },
            ],
        },
    },
    thresholds: {
        'http_req_duration{type:list}': ['p(95)<200'],
        'http_req_failed': ['rate<0.01'],
    },
};

export default function () {
    const res = listSchedules(DEP, ARR, FROM_DATE);
    check(res, { 'list 200': (r) => r.status === 200 });
    sleep(0.1);
}
