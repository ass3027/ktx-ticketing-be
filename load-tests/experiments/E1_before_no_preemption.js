/**
 * E1 Before: 선점 제어 없음 → oversell 발생 확인
 *
 * 전제: 앱을 booking.preemption.enabled=false 로 기동해야 함
 *   docker compose 환경변수 또는 IDE VM 옵션:
 *     -Dbooking.preemption.enabled=false
 *
 * 목적: 선점(SREM) 없이 1,000 VUser 동일 좌석 경쟁 → oversell 수치 기록
 * L1 과 동일 부하 프로파일, Before/After 비교용
 *
 * 주의: T4-9 에서 preemption 토글 구현 후 실행. 현재는 스크립트만 준비.
 */
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { SCHEDULE_ID, SEAT_INVENTORY_ID } from '../common/config.js';
import { userIds, getEntryToken, bookSeat } from '../common/helpers.js';

const reserveOk = new Counter('reserve_ok');

export const options = {
    scenarios: {
        E1_before: {
            executor: 'shared-iterations',
            vus: 1000,
            iterations: 1000,
            maxDuration: '2m',
        },
    },
    // 임계값 느슨하게 — oversell 발생을 관측하는 것이 목적
    thresholds: {
        'http_req_failed': ['rate<0.99'],
    },
};

export default function () {
    const userId = userIds[(__VU - 1) % userIds.length];

    const token = getEntryToken(userId, SCHEDULE_ID);
    if (!token) return;

    const res = bookSeat(token, SEAT_INVENTORY_ID);
    if (res.status === 201) {
        reserveOk.add(1);
        check(res, { 'E1-before 201': () => true });
    }
}

export function handleSummary(data) {
    const ok = data.metrics['reserve_ok'] ? data.metrics['reserve_ok'].values['count'] : 0;
    console.log(`[E1 Before] reserve_ok=${ok} (선점 없음 → oversell 발생 예상)`);
    return {};
}
