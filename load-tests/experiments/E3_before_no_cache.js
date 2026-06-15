/**
 * E3 Before: 조회 캐시 없음 (DB 직접 집계) → 조회 p95·DB 부하 기록
 *
 * 전제: 앱을 booking.query-cache.enabled=false 로 기동
 *   -Dbooking.query-cache.enabled=false
 *
 * 주의: T4-9 에서 SCARD 경로에 토글 구현 후 실행. 현재는 스크립트만 준비.
 * 캐시 off 상태 = DB 에서 직접 잔여석 COUNT 집계 (현재 구현은 SCARD 사용 중)
 *
 * L3 와 동일 부하, E3_after 와 비교
 */
import { check, sleep } from 'k6';
import { DEP, ARR, FROM_DATE } from '../common/config.js';
import { listSchedules } from '../common/helpers.js';

export const options = {
    scenarios: {
        E3_before: {
            executor: 'ramping-vus',
            stages: [
                { duration: '1m', target: 2000 },
                { duration: '3m', target: 2000 },
                { duration: '30s', target: 0 },
            ],
        },
    },
    thresholds: {
        'http_req_failed': ['rate<0.05'],
        // 임계값 느슨 — 느린 p95 를 관측하는 것이 목적
    },
};

export default function () {
    const res = listSchedules(DEP, ARR, FROM_DATE);
    check(res, { 'list ok': (r) => r.status === 200 });
    sleep(0.1);
}
