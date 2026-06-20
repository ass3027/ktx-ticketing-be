/**
 * L2: 정상 예매 플로우 — 혼합 부하
 *
 * 목적: 현실적 혼합 부하에서 응답시간/처리량 측정 (S1, S3)
 * 요청 구성: 조회 70% / 입장+예매 25% / 확정 5%
 * 프로파일: 0→1000 VUser ramp-up 2분 → 1000 유지 5분 → ramp-down 1분
 * 합격: 예매 p95 ≤ 500ms, ≥ 200 TPS, 5xx < 1%
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';
import { SCHEDULE_ID, FROM_DATE, DEP, ARR } from '../common/config.js';
import { userIds, getEntryToken, bookSeat, bookAuto, confirmReservation, listSchedules } from '../common/helpers.js';

// 입장 제어(429)·경쟁 패배(409)·매진(410)은 정상 비즈니스 응답 → http_req_failed 에서 제외.
// 이래야 http_req_failed 가 SLO(5xx<1%) 와 같은 의미가 된다(진짜 서버 오류/연결 실패만 집계).
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 410, 429));

export const options = {
    scenarios: {
        L2: {
            executor: 'ramping-vus',
            stages: [
                { duration: '2m', target: 1000 },
                { duration: '5m', target: 1000 },
                { duration: '1m', target: 0 },
            ],
        },
    },
    thresholds: {
        'http_req_duration{type:reserve}': ['p(95)<500', 'p(99)<1000'],
        'http_req_duration{type:list}': ['p(95)<200'],
        'http_req_failed': ['rate<0.01'],
        'http_reqs': ['rate>200'], // S3 처리량 SLO(동시 1,000 VU 에서 ≥200 TPS) 자동 단언
    },
};

export default function () {
    const roll = Math.random();

    if (roll < 0.70) {
        // 조회 (70%)
        const res = listSchedules(DEP, ARR, FROM_DATE);
        check(res, { 'list 200': (r) => r.status === 200 });
        sleep(0.5);
        return;
    }

    // 입장 + 예매 (25%)  또는  확정 (5%)
    const userId = userIds[(__VU - 1) % userIds.length];
    const token = getEntryToken(userId, SCHEDULE_ID);
    if (!token) {
        sleep(1);
        return; // 429 — 정상 입장 제어
    }

    if (roll < 0.95) {
        // 예매 (SEAT 50% / AUTO 50%)
        const res = Math.random() < 0.5
            ? bookAuto(token)
            : bookSeat(token, Math.floor(Math.random() * 1000) + 1); // 여러 좌석 분산

        check(res, { 'reserve ok': (r) => [201, 409, 410].includes(r.status) });

        // 예매 성공 시 일부(20%)만 확정
        if (res.status === 201 && Math.random() < 0.2) {
            const reservationId = res.json('reservationId');
            if (reservationId) {
                const confirmRes = confirmReservation(token, reservationId);
                check(confirmRes, { 'confirm ok': (r) => r.status === 200 });
            }
        }
    }

    sleep(1);
}

// 표준 요약(콘솔 표)+JSON 보존 → 처리량·p95/p99·5xx 근거(P4 성능 측정).
export function handleSummary(data) {
    return {
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
        'load-tests/results/L2_summary.json': JSON.stringify(data, null, 2),
    };
}
