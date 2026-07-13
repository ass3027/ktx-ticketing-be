/**
 * L3b: 운행 조회 폭주 — 다중 키(낮은 히트율) 변형
 *
 * 목적: T4-5 ④ 캐시 효과의 적용 범위를 정직하게 보이기 위한 대조. 기본 L3 는 단일 노선·단일 from
 *       으로 캐시 키가 1개 = 히트율 ~100%(핫키 상한 효과)만 잰다. 여기선 from 을 시드된 50일에 균등
 *       분산해 캐시 키를 50개로 갈라, 미스 빈도(키당 TTL 만료)를 50배로 올린 "리얼한 다키" 조회를 잰다.
 *
 * 키 분산 원리: 시드(DataInitializer)는 서울→부산 50편을 departure_time = (오늘 KST + 1일) 08:00 + i일
 *   (i=0..49)로 깐다. 조회는 departureTime > from 이라, from 을 그 50개 시각으로 돌리면 매 요청이
 *   서로 다른 첫 페이지(=서로 다른 캐시 키 qcache:list:서울:부산:{from}:0:8)를 친다. 모든 응답은 실제
 *   데이터(빈 페이지 아님, 마지막 날짜여도 그 이후 편이 있거나 마지막 1편) — 캐시 값이 유효하다.
 *
 * 나머지(프로파일·게이트·핸들러)는 L3 와 동일 — off/on 비교를 같은 조건에서 하기 위함.
 */
import { check } from 'k6';
import { textSummary } from '../common/k6-summary.js';
import { BASE_URL, DEP, ARR } from '../common/config.js';
import http from 'k6/http';

const RATE = parseInt(__ENV.RATE || '3000');
const HOLD = __ENV.HOLD || '3m';

// 시드 base(오늘 KST + 1일 08:00, DataInitializer 와 동일 규칙)와 일치(B-4). 하드코딩 제거.
// k6 컨테이너 UTC → epoch +9h 로 KST 달력일 이동 후 08:00 고정. 여기서 하루 간격 50개 from 생성.
const KST_OFFSET_MS = 9 * 3600 * 1000;
const _nowKst = new Date(Date.now() + KST_OFFSET_MS);
const BASE_DATE = new Date(Date.UTC(
    _nowKst.getUTCFullYear(), _nowKst.getUTCMonth(), _nowKst.getUTCDate() + 1, 8, 0, 0));
const KEY_COUNT = parseInt(__ENV.KEY_COUNT || '50');

// from 후보 50개(하루씩)를 ISO(초까지, 타임존 없이 LocalDateTime 파싱형)로 미리 만든다.
const FROMS = Array.from({ length: KEY_COUNT }, (_, i) => {
    const d = new Date(BASE_DATE.getTime() + i * 24 * 3600 * 1000);
    // 'YYYY-MM-DDTHH:mm:ss' — 서버 LocalDateTime 파싱 형식(config.FROM_DATE 와 동일 포맷).
    return d.toISOString().slice(0, 19);
});

export const options = {
    scenarios: {
        L3b: {
            executor: 'ramping-arrival-rate',
            startRate: 0,
            timeUnit: '1s',
            stages: [
                { duration: '1m', target: RATE },
                { duration: HOLD, target: RATE },
                { duration: '30s', target: 0 },
            ],
            preAllocatedVUs: 1000,
            maxVUs: 5000,
        },
    },
    thresholds: {
        'http_req_duration{type:list}': ['p(95)<200'],
        'http_req_failed': ['rate<0.01'],
        'checks': ['rate>0.99'],
        'dropped_iterations': ['count==0'],
    },
    discardResponseBodies: true,
};

export default function () {
    // 요청마다 50개 from 중 하나를 라운드 진행 없이 무작위 선택 → 키가 고르게 분산.
    const from = FROMS[Math.floor(Math.random() * FROMS.length)];
    const res = http.get(
        `${BASE_URL}/api/schedules?dep=${encodeURIComponent(DEP)}&arr=${encodeURIComponent(ARR)}&from=${encodeURIComponent(from)}`,
        { tags: { type: 'list' } }
    );
    check(res, { 'list 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
    return {
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
        'load-tests/results/L3b_summary.json': JSON.stringify(data, null, 2),
    };
}
