/**
 * L3: 운행 조회 폭주 (읽기 부하)
 *
 * 목적: 읽기 경로 응답·캐시 효과 측정 (S2), 실험 E3 기준선
 * 모델: 열린 루프(ramping-arrival-rate) — SLO 가 "X TPS 에서 p95≤200" 구조이므로 도착률(TPS)을
 *       통제 변수로 고정한다. 닫힌 루프(VU+sleep)는 서버가 느려지면 부하가 같이 줄어
 *       (coordinated omission) 꼬리 지연을 숨긴다.
 * 프로파일: 0→RATE TPS ramp 1분 → 3분 유지 → 30s ramp-down
 * 합격: 조회 p95 ≤ 200ms, dropped_iterations==0 (목표 도착률을 실제로 발사했는가 = 전제)
 */
import { check } from 'k6';
import { textSummary } from '../common/k6-summary.js';
import { DEP, ARR, FROM_DATE } from '../common/config.js';
import { listSchedules } from '../common/helpers.js';

// E3 Before/After 는 동일 도착률로 비교해야 하므로 기본값 고정(필요시 RATE 로 주입).
const RATE = parseInt(__ENV.RATE || '3000');
// 유지 구간 길이. 기본 3m(기존 비교 하위호환). p95 는 3,000TPS 에서 이미 과표본이라(90s=27만 요청)
// 정상상태(ramp 후)만 확보되면 단축해도 점추정이 안정 → 남는 시간은 회차 반복에 써 회차 간 분산을 본다.
const HOLD = __ENV.HOLD || '3m';

export const options = {
    scenarios: {
        L3: {
            executor: 'ramping-arrival-rate',
            startRate: 0,
            timeUnit: '1s',
            stages: [
                { duration: '1m', target: RATE },
                { duration: HOLD, target: RATE },
                { duration: '30s', target: 0 },
            ],
            // 도착률을 못 채우면 dropped_iterations 발생 → 측정 무효. Little's law(≈RATE×p95)
            // 기준 여유분으로 사전 할당하고, 지연 상승 시 maxVUs 까지 자동 증설.
            preAllocatedVUs: 1000,
            maxVUs: 5000,
        },
    },
    thresholds: {
        'http_req_duration{type:list}': ['p(95)<200'],
        'http_req_failed': ['rate<0.01'],
        'checks': ['rate>0.99'], // check 는 기록만 하므로 게이트로 승격 — 200 누수 시 자동 불합격
        'dropped_iterations': ['count==0'], // 생성기가 목표 TPS 를 실제로 발사했는가(전제 단언)
    },
    discardResponseBodies: true, // body 미사용 → 생성기 메모리 절약
};

export default function () {
    const res = listSchedules(DEP, ARR, FROM_DATE);
    check(res, { 'list 200': (r) => r.status === 200 });
    // 열린 루프: 페이싱은 arrival-rate 가 담당 → sleep 제거(넣으면 도착률 모델이 깨진다).
}

// E3 기준선 — p95/p99·http_reqs 를 JSON 으로 보존해 E3 Before/After 비교 근거로 쓴다.
export function handleSummary(data) {
    return {
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
        'load-tests/results/L3_summary.json': JSON.stringify(data, null, 2),
    };
}
