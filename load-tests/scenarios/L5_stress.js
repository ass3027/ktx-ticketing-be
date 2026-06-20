/**
 * L5: 임계점 탐색 (Stress / Breakpoint)
 *
 * 목적: 시스템이 무너지는 처리량(TPS)을 숫자로 파악 (S6) → 활성자 상한 K 역산
 * 모델: 열린 루프(ramping-arrival-rate) — 임계점은 "도착률을 올리다 깨지는 지점"이므로 부하
 *       변수는 VU 가 아니라 TPS 여야 한다.
 * 프로파일: 도착률 계단식 증가 500→1000→2000→4000 TPS, 각 단계 3분 유지
 * 산출: p99 급증 / 에러율 급증 / 달성 TPS(http_reqs)가 목표를 못 따라가는 지점 = 임계점
 *
 * 주의: dropped_iterations 를 *합격 게이트로 걸지 않는다*. 임계점을 넘으면 생성기가 목표 TPS 를
 *       못 채우는 것이 정상 신호다. 단, 그게 서버 한계인지 생성기(LG) 한계인지는 LG 자원
 *       (CPU<80%·메모리)을 함께 봐야 구분된다 — dropped 가 늘기 시작하는 시점의 달성 TPS 를
 *       임계점으로 읽는다.
 */
import { check } from 'k6';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';
import { SCHEDULE_ID, DEP, ARR, FROM_DATE } from '../common/config.js';
import { userIds, getEntryToken, bookAuto, listSchedules } from '../common/helpers.js';

export const options = {
    scenarios: {
        L5: {
            executor: 'ramping-arrival-rate',
            startRate: 0,
            timeUnit: '1s',
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
            preAllocatedVUs: 2000,
            maxVUs: 8000, // 지연 상승 시 도착률 유지를 위해 크게 — 결국 LG 한계에 닿으면 dropped 로 드러남
        },
    },
    thresholds: {
        // 임계점 탐색 — 고의로 느슨하게 설정해 붕괴 구간까지 실행. dropped 는 게이트가 아니라 신호.
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
        if (!token) return;

        const res = bookAuto(token);
        check(res, { 'reserve ok': (r) => [201, 409, 410, 429, 503].includes(r.status) });
    }
    // 열린 루프: sleep 제거 — 페이싱은 arrival-rate 가 담당.
}

export function handleSummary(data) {
    const dropped = data.metrics['dropped_iterations']
        ? data.metrics['dropped_iterations'].values['count'] : 0;
    if (dropped > 0) {
        console.warn(`[L5] dropped_iterations=${dropped} — 이 지점부터 달성 TPS<목표. 서버 한계인지 LG 한계인지 LG 자원과 대조해 임계점 판정.`);
    }
    return {
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
        'load-tests/results/L5_summary.json': JSON.stringify(data, null, 2),
    };
}
