/**
 * L2b: 자동 배정 처리량
 *
 * 목적: 경합 분산 시 최대 처리량 측정 (AUTO 모드), L1(직접 선택) 대비 비교
 * 프로파일: 1,000 VUser 가 mode=AUTO 로 동일 스케줄 좌석 소진까지
 * 검증: 좌석 1,000석 만큼만 성공, 이후 SoldOut(410) 수렴
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';
import { SCHEDULE_ID } from '../common/config.js';
import { userIds, getEntryToken, bookAuto } from '../common/helpers.js';

const reserveOk = new Counter('reserve_ok');
const soldOut = new Counter('sold_out');

// 매진(410)·경쟁 패배(409)는 정상 비즈니스 응답 → http_req_failed 에서 제외.
// init 단계에서 호출해 모든 VU 에 적용한다. 이래야 http_req_failed 가 SLO(5xx<1%) 와
// 같은 의미가 된다(진짜 서버 오류/연결 실패만 집계).
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 410));

export const options = {
    scenarios: {
        L2b: {
            executor: 'shared-iterations',
            vus: 1000,
            iterations: 2000, // 좌석(1000)보다 많이 시도 → 매진 수렴 확인
            maxDuration: '5m',
        },
    },
    thresholds: {
        'http_req_duration{type:reserve}': ['p(95)<500'],
        'http_req_failed': ['rate<0.01'],
        // 성공 건 = 좌석 수 1000 이하 (정합성)
        reserve_ok: ['count<=1000'],
    },
};

export default function () {
    const userId = userIds[(__VU - 1) % userIds.length];

    const token = getEntryToken(userId, SCHEDULE_ID);
    if (!token) return;

    const res = bookAuto(token);

    if (res.status === 201) {
        reserveOk.add(1);
        check(res, { 'AUTO 201': () => true });
    } else if (res.status === 410) {
        soldOut.add(1);
        check(res, { 'AUTO 410 soldOut': () => true }); // 매진 정상
    } else {
        check(res, { 'AUTO unexpected': () => false });
    }
}

// 표준 요약(콘솔 표)+JSON 보존 → 처리량·p95/p99·매진 수렴 근거(P4 성능 측정).
export function handleSummary(data) {
    return {
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
        'load-tests/results/L2b_summary.json': JSON.stringify(data, null, 2),
    };
}
