/**
 * L1: 직접 선택 — 단일 좌석 동시 경쟁 (정합성)
 *
 * 목적: 1,000 VUser 가 동일 좌석에 동시 SEAT 예매 → 성공 정확히 1건, oversell == 0 (S4)
 * 합격: reserve_ok count == 1 (2건 이상이면 오버셀 → 실패). 최종 판정은 DB(post_run_check.sql).
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
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';
import { SCHEDULE_ID, SEAT_INVENTORY_ID } from '../common/config.js';
import { userIds, getEntryToken, bookSeat } from '../common/helpers.js';

// 오버셀은 reserve_ok==1 threshold 로 간접 검증, 최종 판정은 DB(post_run_check.sql).
const reserveOk = new Counter('reserve_ok');
// 전제 게이트: 입장 토큰 실패(429 차단/refused) 건수. count==0 이라야 "1,000 전원 경쟁" 성립.
const entryFail = new Counter('entry_fail');

// 경쟁 패배(409)·매진(410)·입장 제어(429)는 정상 응답 → http_req_failed 에서 제외.
// 이래야 http_req_failed 가 SLO(5xx<1%) 와 같은 의미가 된다(진짜 서버 오류만 집계).
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 410, 429));

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
        reserve_ok: ['count==1'],
        http_req_failed: ['rate<0.01'], // 999 패배자가 깨끗한 4xx 인지 — 5xx 누수 가드
        entry_fail: ['count==0'],       // 전제 붕괴(입장 우회 누락·refused) 시 침묵 통과 방지
    },
};

export default function () {
    const userId = userIds[(__VU - 1) % userIds.length];

    const token = getEntryToken(userId, SCHEDULE_ID);
    if (!token) { entryFail.add(1); return; } // 토큰 실패는 전제 붕괴 신호 → 카운트해 노출

    const res = bookSeat(token, SEAT_INVENTORY_ID);

    if (check(res, { 'status is 201': (r) => r.status === 201 })) {
        reserveOk.add(1);
    }
}

// 성공 2건 이상이면 오버셀 경고. 표준 요약(콘솔 표)+JSON 으로 p95/p99·http_reqs 보존.
export function handleSummary(data) {
    const ok = data.metrics['reserve_ok'] ? data.metrics['reserve_ok'].values['count'] : 0;
    if (ok > 1) {
        console.error(`[L1] OVERSELL DETECTED: reserve_ok=${ok} (expected 1)`);
    }
    const ef = data.metrics['entry_fail'] ? data.metrics['entry_fail'].values['count'] : 0;
    if (ef > 0) {
        console.error(`[L1] 전제 붕괴: entry_fail=${ef} — 입장 우회(max-active) 누락이나 refused 의심. 측정 무효.`);
    }
    return {
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
        'load-tests/results/L1_summary.json': JSON.stringify(data, null, 2),
    };
}
