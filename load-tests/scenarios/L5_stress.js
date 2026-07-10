/**
 * L5: 임계점 탐색 (Stress / Breakpoint)
 *
 * 목적: 시스템이 무너지는 처리량(TPS)을 숫자로 파악 (S6) → 활성자 상한 K 역산
 * 모델: 열린 루프(ramping-arrival-rate) — 임계점은 "도착률을 올리다 깨지는 지점"이므로 부하
 *       변수는 VU 가 아니라 TPS 여야 한다.
 * 프로파일: 도착률 계단식 증가 500→1000→2000→4000 TPS, 각 단계 3분 유지
 * 산출: p99 급증 / 진짜 5xx 급증 / 백프레셔(429·503) 시작점 / 달성 TPS(http_reqs)가 목표를
 *       못 따라가는 지점 = 임계점
 *
 * <b>예매 경로를 끝까지 살린다(churn)</b> — bookAuto 는 좌석을 소비한다. 그냥 두면 4000 TPS×40%
 * ≈ 1,600 예매/초로 50k 재고가 ~30초면 매진돼 이후 전 구간이 410 fast-path 만 타 측정이 오염된다.
 * 그래서 (1) 예매 성공 시 1초 점유 후 취소(churn)해 좌석을 재순환하고, (2) {@link SCHEDULE_COUNT}
 * 개 스케줄에 분산한다. 정상상태 점유 ≈ 도착률×점유시간 ≈ 1,600석 / 50스케줄 = 스케줄당 ~32석
 * 이라 재고가 마르지 않고 매 반복이 진짜 예매+취소 write 경로를 탄다.
 *
 * <b>http_req_failed 의 의미</b> — 409/410/429/503 은 설계상 의도된 응답이라 실패에서 제외한다
 * (아래 setResponseCallback). 그래야 http_req_failed 가 "진짜 5xx/연결오류 = 붕괴 신호"만
 * 의미한다. 입장 제어가 흘려보낸 비율은 별도 `backpressure` Rate 로 분리 측정해 K 를 역산한다.
 *
 * <b>판독</b> — 전 구간을 뭉친 단일 집계로는 "어느 TPS 단계에서 깨졌는지" 못 찾는다. 단계별/경로별로
 * 끊어 보려면 시계열로 실행한다:
 *   k6 run --out json=load-tests/results/L5_timeseries.json L5_stress.js   (또는 :5665 대시보드)
 * 각 plateau 구간의 달성 TPS·reserve p99·backpressure·dropped 를 끊어 읽어 임계점을 판정한다.
 *
 * <b>환경 타당성</b> — 4000 TPS 에서는 Windows/Docker 의 NAT·ephemeral 포트 한계가 앱보다 먼저
 * 닿을 수 있다. 달성 TPS 한계 지점에서 http_req_failed 안의 연결오류(status 0)와 LG CPU(<80%)·
 * 메모리를 함께 확인해 "서버 한계 vs 생성기/네트워크 한계"를 구분한다(컨테이너↔컨테이너 실행 권장,
 * §K6_Port_Exhaustion_Troubleshooting.md).
 *
 * <b>재현성</b> — 회차 간 `make reset-seed && docker compose restart app` 으로 좌석/Redis 초기화.
 */
import { check, sleep } from 'k6';
import http from 'k6/http';
import exec from 'k6/execution';
import { Rate, Counter } from 'k6/metrics';
import { textSummary } from '../common/k6-summary.js';
import { SCHEDULE_COUNT, DEP, ARR, FROM_DATE } from '../common/config.js';
import { userIds, getEntryToken, bookAuto, cancelReservation, listSchedules, checkConsistency } from '../common/helpers.js';

// 409 경쟁패배·410 매진·429/503 입장제어 = 설계상 의도된 응답 → 서버오류 아님.
// 이래야 http_req_failed 가 "진짜 5xx/연결오류 = 붕괴 신호"만 의미한다.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 410, 429, 503));

// K 역산용: 예매 경로가 입장 제어(429/503)에 막힌 비율. http_req_failed 와 분리해 "백프레셔 시작점"을 본다.
const backpressure = new Rate('backpressure');
const consistencyViolation = new Counter('consistency_violation'); // 부하 후 정합성 위반 합계 (U-2)

const HOLD_SECONDS = 1; // 예매 후 좌석 점유 시간 모델 — 이만큼 뒤 취소(churn)해 재고를 재순환.

export const options = {
    // list 응답(대용량)은 status 만 보고 본문 미사용 → 버려서 8000 VU 메모리 절약.
    // token/예약ID 가 필요한 entry·reserve 요청만 helpers 에서 responseType:'text' 로 본문 유지.
    discardResponseBodies: true,
    // teardown 이 reconcile/sweep 수렴(90s)을 기다린 뒤 audit 하므로 기본 60s 를 넘긴다.
    teardownTimeout: '150s',
    scenarios: {
        L5: {
            executor: 'ramping-arrival-rate',
            startRate: 0,
            timeUnit: '1s',
            stages: [
                { duration: '1m',  target: 500  },
                { duration: '3m',  target: 500  },
                { duration: '1m',  target: 1000 },
                { duration: '3m',  target: 1000 },
                { duration: '1m',  target: 2000 },
                { duration: '3m',  target: 2000 },
                { duration: '1m',  target: 4000 },
                { duration: '3m',  target: 4000 },
                { duration: '30s', target: 0    },
            ],
            // 필요 VU ≈ 도착률 × iteration시간. iteration ≈ 0.6×list(~50ms) + 0.4×예매(hold 1s+왕복)
            // ≈ 0.47s → 4k TPS 엔 ~1,900 VU. 지연 상승분 여유로 상한 4,000(필요의 2배)면 충분하다.
            // 8,000 은 과다 — k6 가 VM 메모리를 불필요하게 2배 잡아 4GB VM 을 OOM 시켰다(T4-7 실측).
            // 상한 초과분은 dropped_iterations 로 정직하게 드러나 오히려 임계점 신호가 된다.
            preAllocatedVUs: 2500,
            maxVUs: 4000,
        },
    },
    thresholds: {
        // 임계점 탐색 — 고의로 느슨하게. dropped 는 게이트가 아니라 신호. 진짜 5xx/연결오류만 집계됨.
        'http_req_failed': ['rate<0.5'],
        // 관찰용 SLO 참조선(abortOnFail 없음 → 중단 안 함). 붕괴 구간의 위반은 정상 신호다.
        // 태그 스코프로 걸어야 summary 에 경로별 p99 가 분리 노출돼 어느 경로가 먼저 무너지는지 보인다.
        'http_req_duration{type:reserve}': ['p(99)<1000'],
        'http_req_duration{type:list}': ['p(95)<200'],
        // 붕괴 구간을 거쳐도 좌석 정합성은 불변식 — reconcile/sweep 수렴 대기 후 audit(teardown)으로 판정.
        consistency_violation: ['count==0'],
    },
};

export default function () {
    // 전역 단조 증가 인덱스 — arrival-rate 의 VU 재사용과 무관하게 user/schedule 다양성 확보.
    const i = exec.scenario.iterationInTest;
    const roll = Math.random();

    if (roll < 0.6) {
        const res = listSchedules(DEP, ARR, FROM_DATE);
        check(res, { 'list ok': (r) => r.status === 200 });
        return;
    }

    const userId = userIds[i % userIds.length];
    const scheduleId = 1 + (i % SCHEDULE_COUNT); // 50개 스케줄에 분산 → 단일 avail 키 매진 회피

    const token = getEntryToken(userId, scheduleId);
    if (!token) { backpressure.add(true); return; } // 입장 단계 차단(429/503) = 의도된 백프레셔

    const res = bookAuto(token);
    const shed = res.status === 429 || res.status === 503;
    backpressure.add(shed);
    check(res, { 'reserve handled': (r) => [201, 409, 410, 429, 503].includes(r.status) });

    if (res.status === 201) {
        const rid = res.json('reservationId');
        sleep(HOLD_SECONDS);                     // 점유 시간 모델 — 페이싱 아님(페이싱은 arrival-rate 담당)
        if (rid) cancelReservation(token, rid);  // churn: 좌석(avail SADD)·슬롯(leave DECR)·토큰 반환
    }
    // 비-201(드묾: AUTO 라 409 거의 없고 410 은 churn+분산으로 차단)은 leave 엔드포인트가 없어
    // 슬롯이 토큰 TTL 만료까지 남는다. 빈도가 낮아 무시. 빈번해지면 그 자체가 붕괴 신호다.
}

// 부하 종료 후 정합성 audit. 붕괴 구간에서 reconcile(60s)/sweep(30s)이 밀렸을 수 있어, 수렴 시간을
// 준 뒤 audit 해 일시 드리프트를 영구 위반과 구분한다. availDrift missing 은 grace(5m) 로도 보호된다.
export function teardown() {
    sleep(90); // reconcile 1주기(60s) + sweep(30s) 수렴 여유
    const violations = checkConsistency();
    if (violations !== 0) {
        consistencyViolation.add(violations === -1 ? 1 : violations);
        console.error(`[L5] CONSISTENCY VIOLATION: ${violations} (audit /internal/consistency)`);
    }
}

export function handleSummary(data) {
    const dropped = data.metrics['dropped_iterations']
        ? data.metrics['dropped_iterations'].values['count'] : 0;
    if (dropped > 0) {
        console.warn(`[L5] dropped_iterations=${dropped} — 이 지점부터 달성 TPS<목표. 서버 한계인지 LG 한계인지 LG 자원과 대조해 임계점 판정.`);
    }
    const bp = data.metrics['backpressure'] ? data.metrics['backpressure'].values['rate'] : 0;
    console.log(`[L5] backpressure(429/503) rate=${(bp * 100).toFixed(1)}% — 입장 제어가 흘려보낸 비율. K 역산 입력.`);
    return {
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
        'load-tests/results/L5_summary.json': JSON.stringify(data, null, 2),
    };
}
