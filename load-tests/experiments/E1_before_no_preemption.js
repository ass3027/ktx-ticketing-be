/**
 * E1 Before — 선점(SREM) off: 경합을 DB 에서만 해소 → 처리량/지연/pool 비용 관측
 *
 * 전제: 앱을 booking.preemption.enabled=false 로 기동(러너 -PreemptionEnabled false).
 *       입장 제어는 우회(-AdmissionMax 2000)해야 1,000 이 모두 좌석 경쟁에 도달한다.
 *
 * 재정의(T4-9): "선점 off → oversell" 은 성립하지 않는다 — @Version/uk_active_seat 가 여전히 방어해
 * oversell 은 양쪽 0(= reserve_ok==1). E1 이 보는 것은 <b>경합 위치(DB row-lock vs Redis 게이트)의 비용</b>:
 * 선점 off 면 1,000 이 전부 DB 로 내려가 좌석 row-lock 에 직렬화 + pool(10) 포화 → 처리량 붕괴·reserve p95 폭등,
 * 경합 패배자는 409(OptLock/uk_active_seat→advice 매핑) + 일부 pool-timeout 5xx(구조적 비용).
 * After(선점 on)와 동일 부하·동일 지표로 대조한다(reserve TPS·p95/p99·server_errors·pool).
 *
 * 합격/관측: oversell==0(reserve_ok==1)·consistency==0 은 <b>유지</b>(정확성). 처리량/지연 열화는 실패가 아니라
 * 관측 대상이므로 http_req_failed threshold 는 느슨히 두고 server_errors 로 5xx 를 기록한다.
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { textSummary } from '../common/k6-summary.js';
import { SCHEDULE_ID, SEAT_INVENTORY_ID } from '../common/config.js';
import { userIds, getEntryToken, bookSeat, consistencyDetail } from '../common/helpers.js';

const reserveOk = new Counter('reserve_ok');
const serverErrors = new Counter('server_errors');           // reserve 경로 5xx(=pool-timeout 등 구조적 비용) 관측
// 오버셀 게이트 — statusViolation(좌석당 활성 2건↑)만 센다. availDrift 는 선점 off 가 avail 을 우회해
// 생기는 예상된 드리프트(HELD 좌석이 avail 에 잔존, reconcile 이 60s 내 치유)라 게이트 대상이 아니다.
const oversell = new Counter('oversell');

const VU_COUNT = 1000;

// 경쟁 패배(409)·매진(410)·입장(429)은 정상 → http_req_failed 에서 제외(= 5xx 만 집계).
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 410, 429));

export const options = {
    scenarios: {
        E1_before: {
            executor: 'shared-iterations',
            vus: VU_COUNT,
            iterations: VU_COUNT,
            // 선점 off 면 1,000 이 전부 DB row-lock/pool 에 직렬화돼 완료가 느리다 → 컷오프로 미완 발생 않게 여유.
            maxDuration: '3m',
        },
    },
    thresholds: {
        reserve_ok: ['count==1'],              // 선점 off 여도 오버셀 0(@Version/uk_active_seat)
        oversell: ['count==0'],                // SoT 게이트 — 좌석당 활성 2건↑(진짜 오버셀)
        'http_req_failed': ['rate<0.99'],      // 느슨 — pool-timeout 5xx 는 구조적 비용(관측 대상, 실패 아님)
        // reserve 전용 지연을 기록(entry/audit 오염 제외). Before 는 관측 목적이라 느슨(서브메트릭 생성용).
        'http_req_duration{type:reserve}': ['p(95)<60000'],
    },
};

export function setup() {
    const tokens = [];
    for (let i = 0; i < VU_COUNT; i++) {
        const token = getEntryToken(userIds[i], SCHEDULE_ID);
        if (!token) {
            throw new Error(
                `[E1-before] setup 입장 토큰 실패 @user=${userIds[i]} (i=${i}) — ` +
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
    // 오버셀(statusViolation)만 게이트. availDrift 는 선점 off 의 예상 산물이라 관측만 한다.
    if (d.statusViolation !== 0) {
        oversell.add(d.statusViolation === -1 ? 1 : d.statusViolation);
        console.error(`[E1-before] OVERSELL(statusViolation)=${d.statusViolation}`);
    }
    console.log(`[E1-before] audit availDrift=${d.availDrift}(선점 off 예상), expiredHeld=${d.expiredHeld}, statusViolation=${d.statusViolation}`);
}

export function handleSummary(data) {
    const ok = data.metrics['reserve_ok'] ? data.metrics['reserve_ok'].values['count'] : 0;
    const errs = data.metrics['server_errors'] ? data.metrics['server_errors'].values['count'] : 0;
    console.log(`[E1 Before] reserve_ok=${ok} (oversell 0 유지), server_errors=${errs} (구조적 비용 관측)`);
    if (ok > 1) console.error(`[E1-before] OVERSELL DETECTED: reserve_ok=${ok}`);
    const prefix = __ENV.RESULT_PREFIX || 'E1_before';
    return {
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
        [`load-tests/results/${prefix}_summary.json`]: JSON.stringify(data, null, 2),
    };
}
