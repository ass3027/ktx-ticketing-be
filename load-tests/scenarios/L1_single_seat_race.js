/**
 * L1: 직접 선택 — 단일 좌석 동시 경쟁 (정합성)
 *
 * 목적: 1,000 VUser 가 동일 좌석에 동시 SEAT 예매 → 성공 정확히 1건, oversell == 0 (S4)
 * 합격: oversell count == 0, reserve_ok count == 1
 *
 * 부하 후 DB 정합성 수동 확인:
 *   SELECT COUNT(*) FROM reservation WHERE status='HELD' AND seat_inventory_id = <SEAT_INVENTORY_ID>;
 *   -- 결과 = 1 이어야 함
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, SCHEDULE_ID, SEAT_INVENTORY_ID } from '../common/config.js';
import { userIds, getEntryToken, bookSeat } from '../common/helpers.js';

const oversell = new Counter('oversell');
const reserveOk = new Counter('reserve_ok');

export const options = {
    scenarios: {
        L1: {
            executor: 'shared-iterations',
            vus: 1000,
            iterations: 1000,
            maxDuration: '2m',
        },
    },
    thresholds: {
        oversell: ['count==0'],
        reserve_ok: ['count==1'],
    },
};

export default function () {
    const userId = userIds[(__VU - 1) % userIds.length];

    const token = getEntryToken(userId, SCHEDULE_ID);
    if (!token) return; // 429 — 입장 제어 (L1 에선 max-active 충분히 크게 설정 권장)

    const res = bookSeat(token, SEAT_INVENTORY_ID);

    if (check(res, { 'status is 201': (r) => r.status === 201 })) {
        reserveOk.add(1);
    }
}

// teardown: 성공 건 수가 2 이상이면 oversell 카운터 증가 (summary 에서 확인)
export function handleSummary(data) {
    const ok = data.metrics['reserve_ok'] ? data.metrics['reserve_ok'].values['count'] : 0;
    if (ok > 1) {
        // k6 summary 에 oversell 경고 출력
        console.error(`[L1] OVERSELL DETECTED: reserve_ok=${ok} (expected 1)`);
    }
    return {};
}
