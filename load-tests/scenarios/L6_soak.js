/**
 * L6: 지속 부하 (Soak)
 *
 * 목적: 누수/누적 문제 (메모리, 락, HELD 만료) 검증
 * 프로파일: 300 VUser 30분 유지 (중간 부하) — ramp 2m → plateau 30m → ramp-down 1m
 *
 * 합격 — soak 은 "집계 SLO"가 아니라 "시간에 따라 나빠졌나 + 부하 후 복구됐나"로 판정한다:
 *  (1) <b>응답시간 우상향 없음</b> — 집계 p95/p99 로는 추세를 못 잡는다(서서히 새도 평균이 SLO 안이면
 *      통과). 시계열로 실행해 시작 5분 윈도우 vs 종료 5분 윈도우의 reserve p95 를 비교한다:
 *        k6 run --out json=load-tests/results/L6_timeseries.json L6_soak.js   (또는 :5665 대시보드)
 *      종료 윈도우가 시작 윈도우보다 유의하게 높으면(우상향) 누수 의심 → 불합격.
 *  (2) <b>HELD 만료 후 복구</b> — 부하 종료 후 HELD TTL(5분) 경과를 기다린 뒤 post_run_check.sql 로:
 *        ⑤ expired_held_count == 0 (만료 스케줄러가 HELD 를 다 회수했나)
 *        ④ DB AVAILABLE 수 == Redis SCARD avail:{id} (좌석/카운터 드리프트 없나 — 분산한 스케줄
 *           몇 개를 표본으로 대조)
 *      집계 threshold(아래)는 5xx·SLO 의 1차 게이트일 뿐, 복구 판정은 이 사후 SoT 검증이 한다.
 *
 * 재현성: 회차 간 `make reset-seed && docker compose restart app` 으로 좌석/Redis 초기화.
 *
 * 좌석 소진 방지: bookAuto + confirm(영구 SOLD)은 좌석을 영구 소비한다. 단일 스케줄(1,000석)이면
 * ~1분이면 매진돼 이후 전 구간이 410 fast-path 라 soak 대상(예매·HELD 경로)이 사라진다. 그래서
 * SCHEDULE_COUNT 개 스케줄에 분산한다. 영구 소비 ≈ 20 SOLD/s × 1,800s ≈ 36k < 50k 재고라 30분
 * 지속 가능(더 긴 soak 은 confirm 비율을 낮추거나 일부 cancel 로 재순환).
 */
import { check, sleep } from 'k6';
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';
import { SCHEDULE_COUNT, DEP, ARR, FROM_DATE } from '../common/config.js';
import { userIds, getEntryToken, bookAuto, confirmReservation, listSchedules } from '../common/helpers.js';

// 409 경쟁패배·410 매진·429/503 입장제어 = 설계상 의도된 응답 → 서버오류 아님.
// 이래야 http_req_failed: rate<0.01 이 "진짜 5xx<1%" SLO 자동 단언이 된다.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 410, 429, 503));

// 전제 가시화(게이트 아님): 입장 제어 차단·매진 횟수. 종료 후 비정상적으로 크면 "soak 이 예매
// 경로를 못 탔다(전제 붕괴)"는 신호 — 침묵 통과를 막는다.
const entryShed = new Counter('entry_shed');
const soldOut = new Counter('sold_out');

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
        'http_req_failed': ['rate<0.01'], // 보정 후: 진짜 5xx<1%
        'checks': ['rate>0.99'],          // confirm/list 실패를 exit code 로 승격(락·생명주기 누수 신호)
    },
};

export default function () {
    // ramping-vus 는 VU 가 고정 재사용되므로 __VU 인덱싱은 같은 user 반복. 전역 단조 인덱스로 분산.
    const i = exec.scenario.iterationInTest;
    const userId = userIds[i % userIds.length];

    if (Math.random() < 0.6) {
        const res = listSchedules(DEP, ARR, FROM_DATE);
        check(res, { 'list 200': (r) => r.status === 200 });
        sleep(1);
        return;
    }

    const scheduleId = 1 + (i % SCHEDULE_COUNT); // 50개 스케줄에 분산 → 단일 스케줄 매진 회피
    const token = getEntryToken(userId, scheduleId);
    if (!token) { entryShed.add(1); sleep(2); return; } // 입장 제어 차단(429/503) — 카운트해 노출

    const res = bookAuto(token);
    if (res.status === 410) soldOut.add(1); // 매진 = 분산이 부족하다는 신호

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

export function handleSummary(data) {
    const shed = data.metrics['entry_shed'] ? data.metrics['entry_shed'].values['count'] : 0;
    const sold = data.metrics['sold_out'] ? data.metrics['sold_out'].values['count'] : 0;
    if (sold > 0) {
        console.warn(`[L6] sold_out=${sold} — 일부 스케줄 매진. 분산 부족/재고 소진 → 예매 경로 측정 약화. 재고·confirm 비율 점검.`);
    }
    console.log(`[L6] entry_shed=${shed} (입장 제어 차단), sold_out=${sold}. 트렌드는 시계열, HELD 복구는 post_run_check.sql ⑤④로 판정.`);
    return {
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
        'load-tests/results/L6_summary.json': JSON.stringify(data, null, 2),
    };
}
