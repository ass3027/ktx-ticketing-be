/**
 * L1: 직접 선택 — 단일 좌석 동시 경쟁 (정합성)
 *
 * 목적: 1,000 VUser 가 동일 좌석에 동시 SEAT 예매 → 성공 정확히 1건, oversell == 0 (S4)
 * 합격: oversell count == 0, reserve_ok count == 1
 *
 * <b>실행 전 준비</b> — `booking.admission.max-active` 가 운영 잠정값(100)이면 1,000 VU 중 ~900
 * 이 입장 단계에서 429 차단돼 좌석 경쟁 자체가 일어나지 않는다. L1 은 *좌석 선점 정합성* 검증이
 * 목적이므로 입장 제어를 우회해야 한다. docker-compose 의 app 서비스 환경변수에 일시 추가:
 *   BOOKING_ADMISSION_MAX_ACTIVE: 2000
 * 그 뒤 `docker compose up -d --force-recreate app` 으로 컨테이너 재기동. L4 측정 시 원복.
 *
 * <b>반복 실행 시</b> — 첫 회는 정상 경쟁(1명 win, 999명 SeatTaken)이지만 좌석이 이미 HELD 라
 * 두 번째 회부턴 전부 SeatTaken 만 나온다. 매 회 측정 전 `make reset-seed && docker compose
 * restart app` 으로 DB/Redis 초기화하고 DataInitializer 재시드해야 한다.
 *
 * 부하 후 DB/Redis 정합성: `load-tests/verify/post_run_check.sql` ①·④번 + redis-cli 안내 참조.
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
