/**
 * L4: 입장 초과 부하
 *
 * 목적: 활성자 상한 K 초과 시 입장 제어(429) 정상 동작 검증 (S5), 실험 E2 기준선
 * 모델: 열린 루프(ramping-arrival-rate) — K(동시 활성 상한)를 넘기려면 도착률을 고정해 꾸준히
 *       밀어넣어야 한다. 닫힌 루프는 429 로 빠르게 반려된 VU 가 곧장 재시도해 실제 도착률이
 *       응답 속도에 종속된다(초과 부하를 제대로 못 만든다).
 *       + 입장자는 좌석을 1~2s 점유 후 **취소**해 슬롯·좌석을 반환한다(슬롯 churn). 그래야 입장↔거절이
 *       steady state 로 돌고 예매 경로가 3분 내내 부하를 받는다. 취소(확정 아님)로 재고를 회수해
 *       1,000석 소진을 피한다. 점유 think time 이 슬롯 처리량(K/보유시간)을 도착률 아래로 눌러야
 *       초과(429)가 지속된다 — think time 이 없으면 슬롯이 ms 단위로 회전해 전원 입장 → 초과가 사라진다.
 * 프로파일: K 처리량을 초과하는 RATE TPS 로 입장+예매 시도 (기본 500 TPS, K=100 가정)
 * 합격: 초과분이 429 로 흡수(admission_reject_rate>0.5) · 입장/예매 경로 진짜 5xx 없음
 *       (http_req_failed{type:entry}<1%, server_errors<10) · 코어 예매 p95≤500 · dropped_iterations==0
 *
 * 주의: booking.admission.max-active (현재 100) 대비 충분히 높은 RATE 로 설정해야 입장 제어가 발동.
 */
import { check, sleep } from 'k6';
import http from 'k6/http';
import { Counter, Rate } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';
import { SCHEDULE_ID } from '../common/config.js';
import { userIds, getEntryToken, bookAuto, cancelReservation, checkConsistency } from '../common/helpers.js';

// 정상 비즈니스 코드를 http_req_failed 에서 제외 → http_req_failed 가 "진짜 5xx/연결실패"만 의미하게 보정.
// 201 예매성공·204 취소성공·429 입장거부·409 경쟁패배·410 매진.
http.setResponseCallback(http.expectedStatuses(200, 201, 204, 409, 410, 429));

const admissionRejected = new Counter('admission_rejected');   // 429 절대 건수 (관측용)
const admissionRejectRate = new Rate('admission_reject_rate'); // 입장 시도 중 거절 비율 (핵심 단언)
const serverErrors = new Counter('server_errors');             // 예매 경로 5xx (비정상)
const consistencyViolation = new Counter('consistency_violation'); // 부하 후 정합성 위반 합계 (U-2)

const RATE = parseInt(__ENV.RATE || '500');

export const options = {
    scenarios: {
        L4: {
            executor: 'ramping-arrival-rate',
            startRate: 0,
            timeUnit: '1s',
            stages: [
                { duration: '30s', target: RATE },
                { duration: '3m', target: RATE },
                { duration: '30s', target: 0 },
            ],
            preAllocatedVUs: 500,
            maxVUs: 2000,
        },
    },
    thresholds: {
        // ① 핵심 단언: 초과 부하가 실제로 429 로 흡수됐는가. 과반 거절 = 초과가 발생했고 입장 제어가 흡수함.
        //    (이게 없으면 입장 제어가 아예 안 떠도 — 전원 입장해도 — 테스트가 통과한다.)
        admission_reject_rate: ['rate>0.5'],
        // ② 입장 경로의 진짜 서버오류 게이트. responseCallback 보정으로 429(정상)는 빠지고 5xx/연결실패만 집계.
        //    (헬퍼가 429와 5xx를 모두 null 로 뭉개므로, 입장 경로 5xx 는 이 threshold 로만 잡힌다.)
        'http_req_failed{type:entry}': ['rate<0.01'],
        // 예매 경로 5xx — 1% 미만 (429/503 입장 제어는 정상이라 제외)
        server_errors: ['count<10'],
        // 코어 예매 경로 지연 — ③b churn 으로 3분 내내 표본을 확보한 상태에서 판정.
        'http_req_duration{type:reserve}': ['p(95)<500'],
        // 초과 부하 도착률을 실제로 달성했는가 (전제 단언). VU 부족으로 못 채우면 불합격.
        'dropped_iterations': ['count==0'],
        // 부하 후 Redis-DB 정합성(U-2). churn(취소→좌석/슬롯 반환)이 드리프트를 만들지 않았는지 자동 판정.
        consistency_violation: ['count==0'],
    },
};

export default function () {
    const userId = userIds[(__VU - 1) % userIds.length];

    const token = getEntryToken(userId, SCHEDULE_ID);
    if (!token) {
        admissionRejected.add(1);
        admissionRejectRate.add(true); // 입장 거부(429) — 정상
        return;
    }
    admissionRejectRate.add(false); // 입장 성공

    const res = bookAuto(token);
    if (res.status >= 500) {
        serverErrors.add(1);
        check(res, { 'no 5xx': () => false });
        return;
    }
    check(res, { 'reserve ok': (r) => [201, 409, 410].includes(r.status) });

    // ③b 슬롯 churn: 좌석을 1~2s 점유했다가 취소(204)해 슬롯+좌석을 반환 → K 슬롯이 회전한다.
    if (res.status === 201) {
        const reservationId = res.json('reservationId');
        sleep(1 + Math.random()); // 1~2s 점유 (슬롯 보유시간 = 인간 단위라야 초과가 유지됨)
        cancelReservation(token, reservationId); // 취소로 좌석 회수 → 재고 소진 없이 지속 churn
    }
}

// 부하 종료 후 1회 정합성 audit → 위반 합계를 Counter 로 승격(threshold count==0 판정). 읽기전용이라
// 측정 오염 없음. preempt-grace(5m) 이내 선점은 in-flight 로 제외되므로 직후 호출에 위양성 없음.
export function teardown() {
    const violations = checkConsistency();
    if (violations !== 0) {
        consistencyViolation.add(violations === -1 ? 1 : violations);
        console.error(`[L4] CONSISTENCY VIOLATION: ${violations} (audit /internal/consistency)`);
    }
}

export function handleSummary(data) {
    return {
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
        'load-tests/results/L4_summary.json': JSON.stringify(data, null, 2),
    };
}
