/**
 * E1 After: 선점 제어 on (기본값) → oversell == 0
 *
 * 앱 기본 설정(booking.preemption.enabled=true)으로 실행.
 * E1_before 와 동일 부하, 결과를 Before/After 그래프로 비교.
 *
 * 합격: reserve_ok == 1, oversell == 0
 */
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { SCHEDULE_ID, SEAT_INVENTORY_ID } from '../common/config.js';
import { userIds, getEntryToken, bookSeat } from '../common/helpers.js';

const reserveOk = new Counter('reserve_ok');

export const options = {
    scenarios: {
        E1_after: {
            executor: 'shared-iterations',
            vus: 1000,
            iterations: 1000,
            maxDuration: '2m',
        },
    },
    thresholds: {
        reserve_ok: ['count==1'],
    },
};

export default function () {
    const userId = userIds[(__VU - 1) % userIds.length];

    const token = getEntryToken(userId, SCHEDULE_ID);
    if (!token) return;

    const res = bookSeat(token, SEAT_INVENTORY_ID);
    if (res.status === 201) {
        reserveOk.add(1);
        check(res, { 'E1-after 201': () => true });
    }
}

export function handleSummary(data) {
    const ok = data.metrics['reserve_ok'] ? data.metrics['reserve_ok'].values['count'] : 0;
    console.log(`[E1 After] reserve_ok=${ok} (선점 on → 1 이어야 함)`);
    return {};
}
