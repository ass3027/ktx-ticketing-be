/**
 * L5: 임계점 탐색 (Stress)
 *
 * 목적: 시스템이 무너지는 동시 사용자 수를 숫자로 파악 (S6) → 활성자 상한 K 역산
 * 프로파일: VUser 계단식 증가 500→1000→2000→4000, 각 단계 3분 유지
 * 산출: p99 급증 / 에러율 급증 / 처리량이 꺾이는 지점 = 임계점
 */
import { check, sleep } from 'k6';
import { SCHEDULE_ID, DEP, ARR, FROM_DATE } from '../common/config.js';
import { userIds, getEntryToken, bookAuto, listSchedules } from '../common/helpers.js';

export const options = {
    scenarios: {
        L5: {
            executor: 'ramping-vus',
            stages: [
                { duration: '1m',  target: 500  },
                { duration: '3m',  target: 500  },
                { duration: '1m',  target: 1000 },
                { duration: '3m',  target: 1000 },
                { duration: '1m',  target: 2000 },
                { duration: '3m',  target: 2000 },
                { duration: '1m',  target: 4000 },
                { duration: '3m',  target: 4000 },
                { duration: '30s', target: 0    },
            ],
        },
    },
    thresholds: {
        // 임계점 탐색 — 고의로 임계값을 느슨하게 설정해 붕괴 구간까지 실행
        'http_req_failed': ['rate<0.5'],
    },
};

export default function () {
    const roll = Math.random();
    const userId = userIds[(__VU - 1) % userIds.length];

    if (roll < 0.6) {
        const res = listSchedules(DEP, ARR, FROM_DATE);
        check(res, { 'list ok': (r) => r.status === 200 });
    } else {
        const token = getEntryToken(userId, SCHEDULE_ID);
        if (!token) { sleep(0.5); return; }

        const res = bookAuto(token);
        check(res, { 'reserve ok': (r) => [201, 409, 410, 429, 503].includes(r.status) });
    }

    sleep(0.3);
}
