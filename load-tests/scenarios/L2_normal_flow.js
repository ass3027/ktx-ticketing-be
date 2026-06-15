/**
 * L2: 정상 예매 플로우 — 혼합 부하
 *
 * 목적: 현실적 혼합 부하에서 응답시간/처리량 측정 (S1, S3)
 * 요청 구성: 조회 70% / 입장+예매 25% / 확정 5%
 * 프로파일: 0→1000 VUser ramp-up 2분 → 1000 유지 5분 → ramp-down 1분
 * 합격: 예매 p95 ≤ 500ms, ≥ 200 TPS, 5xx < 1%
 */
import { check, sleep } from 'k6';
import { BASE_URL, SCHEDULE_ID, FROM_DATE, DEP, ARR } from '../common/config.js';
import { userIds, getEntryToken, bookSeat, bookAuto, confirmReservation, listSchedules } from '../common/helpers.js';

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
