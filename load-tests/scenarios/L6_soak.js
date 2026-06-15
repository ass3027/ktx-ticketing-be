/**
 * L6: 지속 부하 (Soak)
 *
 * 목적: 누수/누적 문제 (메모리, 락, HELD 만료) 검증
 * 프로파일: 300 VUser 30분 유지 (중간 부하)
 * 합격: 응답시간 우상향 없음, HELD 만료 후 좌석/카운터 정상 복구
 */
import { check, sleep } from 'k6';
import { SCHEDULE_ID, DEP, ARR, FROM_DATE } from '../common/config.js';
import { userIds, getEntryToken, bookAuto, confirmReservation, listSchedules } from '../common/helpers.js';

export const options = {
    scenarios: {
        L6: {
            executor: 'ramping-vus',
            stages: [
                { duration: '2m',  target: 300 },
                { duration: '30m', target: 300 },
                { duration: '1m',  target: 0   },
            ],
        },
    },
    thresholds: {
        'http_req_duration{type:reserve}': ['p(95)<500', 'p(99)<1000'],
        'http_req_duration{type:list}':    ['p(95)<200'],
        'http_req_failed': ['rate<0.01'],
    },
};

export default function () {
    const roll = Math.random();
    const userId = userIds[(__VU - 1) % userIds.length];

    if (roll < 0.6) {
        const res = listSchedules(DEP, ARR, FROM_DATE);
        check(res, { 'list 200': (r) => r.status === 200 });
        sleep(1);
        return;
    }

    const token = getEntryToken(userId, SCHEDULE_ID);
    if (!token) { sleep(2); return; }

    const res = bookAuto(token);
    if (res.status === 201) {
        const reservationId = res.json('reservationId');
        // 예매 후 절반은 확정, 절반은 HELD 만료되도록 방치 (만료 스케줄러 동작 확인)
        if (reservationId && Math.random() < 0.5) {
            const confirmRes = confirmReservation(token, reservationId);
            check(confirmRes, { 'confirm 200': (r) => r.status === 200 });
        }
    }

    sleep(2);
}
