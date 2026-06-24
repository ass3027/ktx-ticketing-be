import http from 'k6/http';
import { SharedArray } from 'k6/data';
import { BASE_URL, USER_COUNT } from './config.js';

// VU 간 공유 사용자 ID 풀 (1 ~ USER_COUNT)
export const userIds = new SharedArray('userIds', () => {
    const ids = [];
    for (let i = 1; i <= USER_COUNT; i++) ids.push(i);
    return ids;
});

/**
 * 입장 토큰 발급.
 * @returns {string|null} token — 429/503 이면 null (입장 제어 정상 동작으로 처리)
 */
export function getEntryToken(userId, scheduleId) {
    const res = http.post(
        `${BASE_URL}/api/entry`,
        JSON.stringify({ userId, scheduleId }),
        // responseType:'text' — discardResponseBodies:true 시나리오(L5)에서도 token 본문을 유지.
        { headers: { 'Content-Type': 'application/json' }, tags: { type: 'entry' }, responseType: 'text' }
    );
    if (res.status === 201) {
        return res.json('token');
    }
    return null; // 429 TOO_MANY_REQUESTS — 입장 제어 정상 동작
}

/**
 * 예매 요청 (SEAT 모드).
 */
export function bookSeat(token, seatInventoryId) {
    return http.post(
        `${BASE_URL}/api/reservations`,
        JSON.stringify({ mode: 'SEAT', seatInventoryId }),
        {
            headers: {
                'Content-Type': 'application/json',
                'X-Entry-Token': token,
            },
            tags: { type: 'reserve' },
        }
    );
}

/**
 * 예매 요청 (AUTO 모드).
 */
export function bookAuto(token) {
    return http.post(
        `${BASE_URL}/api/reservations`,
        JSON.stringify({ mode: 'AUTO' }),
        {
            headers: {
                'Content-Type': 'application/json',
                'X-Entry-Token': token,
            },
            tags: { type: 'reserve' },
            // responseType:'text' — discardResponseBodies:true 시나리오(L5)에서도 reservationId 유지(churn 취소용).
            responseType: 'text',
        }
    );
}

/**
 * 예매 확정.
 */
export function confirmReservation(token, reservationId) {
    return http.post(
        `${BASE_URL}/api/reservations/${reservationId}/confirm`,
        null,
        {
            headers: { 'X-Entry-Token': token },
            // name 태그로 동적 URL(예약 ID)을 묶는다 — 미지정 시 ID마다 time-series 폭발.
            tags: { type: 'confirm', name: '/api/reservations/:id/confirm' },
        }
    );
}

/**
 * 예매 취소 (DELETE). 좌석(avail SADD)과 활성 슬롯(leave DECR)을 모두 반환한다.
 */
export function cancelReservation(token, reservationId) {
    return http.del(
        `${BASE_URL}/api/reservations/${reservationId}`,
        null,
        {
            headers: { 'X-Entry-Token': token },
            // 동적 URL(예약 ID)을 name 태그로 묶는다 — 미지정 시 ID마다 time-series 폭발.
            tags: { type: 'cancel', name: '/api/reservations/:id' },
        }
    );
}

/**
 * 부하 후 정합성 audit 호출(U-2). 앱의 읽기전용 `/internal/consistency`(mutation 없음)를 쳐서
 * availDrift·expiredHeld·statusViolation 합산 위반 수를 얻는다. 시나리오 `teardown()` 에서 호출해
 * `Counter('consistency_violation')` 로 승격하면 `threshold count==0` 으로 ps1 사후 SQL 없이 자동 판정된다.
 *
 * @returns {number} totalViolations — 0 이면 정합. 엔드포인트 호출 실패(비200)는 -1 로 표기해
 *                   teardown 이 위반으로 처리(침묵 통과 방지).
 */
export function checkConsistency() {
    const res = http.get(`${BASE_URL}/internal/consistency`, { tags: { type: 'audit' } });
    if (res.status !== 200) {
        return -1;
    }
    return res.json('availDrift') + res.json('expiredHeld') + res.json('statusViolation');
}

/**
 * 운행 리스트 조회.
 */
export function listSchedules(dep, arr, from) {
    return http.get(
        `${BASE_URL}/api/schedules?dep=${encodeURIComponent(dep)}&arr=${encodeURIComponent(arr)}&from=${encodeURIComponent(from)}`,
        { tags: { type: 'list' } }
    );
}
