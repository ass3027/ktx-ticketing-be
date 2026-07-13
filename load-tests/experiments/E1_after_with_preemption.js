/**
 * E1 After — 선점(SREM) on(기본): 경합을 Redis 앞단에서 흡수 → 처리량/지연/pool 보전
 *
 * 앱 기본(booking.preemption.enabled=true, 러너 -PreemptionEnabled true)으로 실행.
 * E1_before 와 동일 부하(1,000 VU 단일 좌석)·동일 지표로 대조한다.
 *
 * 기대: 999 패배자가 SREM 에서 즉시 409(SeatTaken)로 걸러져 <b>DB 를 건드리지 않음</b> → 좌석 row-lock/
 * pool 포화 없음 → reserve TPS↑·p95/p99↓·server_errors≈0. oversell==0(reserve_ok==1)·consistency==0.
 * Before 대비 지표 개선이 "경합을 Redis 게이트로 앞당긴" 효과다.
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { textSummary } from '../common/k6-summary.js';
import { SCHEDULE_ID, SEAT_INVENTORY_ID } from '../common/config.js';
import { userIds, getEntryToken, bookSeat, consistencyDetail } from '../common/helpers.js';

const reserveOk = new Counter('reserve_ok');
const serverErrors = new Counter('server_errors');
const oversell = new Counter('oversell'); // statusViolation 게이트(선점 on 이면 availDrift 도 0)

const VU_COUNT = 1000;

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 410, 429));

export const options = {
    scenarios: {
        E1_after: {
            executor: 'shared-iterations',
            vus: VU_COUNT,
            iterations: VU_COUNT,
            maxDuration: '2m',
        },
    },
    thresholds: {
        reserve_ok: ['count==1'],
        oversell: ['count==0'],
        'http_req_failed': ['rate<0.01'],      // 엄격 — 선점 on 이면 999 는 깨끗한 409, 5xx 누수 없어야
        // reserve 전용 지연 — 선점 on 은 999 가 Redis 에서 즉시 반려돼 DB 미접촉 → SLO 안이어야.
        'http_req_duration{type:reserve}': ['p(95)<500', 'p(99)<1000'],
    },
};

export function setup() {
    const tokens = [];
    for (let i = 0; i < VU_COUNT; i++) {
        const token = getEntryToken(userIds[i], SCHEDULE_ID);
        if (!token) {
            throw new Error(
                `[E1-after] setup 입장 토큰 실패 @user=${userIds[i]} (i=${i}) — ` +
                `BOOKING_ADMISSION_MAX_ACTIVE 우회 누락 의심. 측정 무효.`);
        }
        tokens.push(token);
    }
    return { tokens };
}

export default function (data) {
    const res = bookSeat(data.tokens[__VU - 1], SEAT_INVENTORY_ID);
    if (check(res, { 'status is 201': (r) => r.status === 201 })) {
        reserveOk.add(1);
    }
    if (res.status >= 500) {
        serverErrors.add(1);
    }
}

export function teardown() {
    const d = consistencyDetail();
    if (d.statusViolation !== 0) {
        oversell.add(d.statusViolation === -1 ? 1 : d.statusViolation);
        console.error(`[E1-after] OVERSELL(statusViolation)=${d.statusViolation}`);
    }
    console.log(`[E1-after] audit availDrift=${d.availDrift}, expiredHeld=${d.expiredHeld}, statusViolation=${d.statusViolation}`);
}

export function handleSummary(data) {
    const ok = data.metrics['reserve_ok'] ? data.metrics['reserve_ok'].values['count'] : 0;
    const errs = data.metrics['server_errors'] ? data.metrics['server_errors'].values['count'] : 0;
    console.log(`[E1 After] reserve_ok=${ok} (선점 on → 1), server_errors=${errs} (≈0 기대)`);
    if (ok > 1) console.error(`[E1-after] OVERSELL DETECTED: reserve_ok=${ok}`);
    const prefix = __ENV.RESULT_PREFIX || 'E1_after';
    return {
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
        [`load-tests/results/${prefix}_summary.json`]: JSON.stringify(data, null, 2),
    };
}
