/**
 * E3 After: 조회 캐시 on (Redis SCARD, 기본값) → 조회 p95·DB 부하 개선 확인
 *
 * 앱 기본 설정으로 실행 (Redis SCARD 경로).
 * E3_before 와 동일 부하, 결과를 Before/After 그래프로 비교.
 *
 * 합격: 조회 p95 ≤ 200ms, DB CPU 부하 E3_before 대비 감소
 */
import { check, sleep } from 'k6';
import { DEP, ARR, FROM_DATE } from '../common/config.js';
import { listSchedules } from '../common/helpers.js';

export const options = {
    scenarios: {
        E3_after: {
            executor: 'ramping-vus',
            stages: [
                { duration: '1m', target: 2000 },
                { duration: '3m', target: 2000 },
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
