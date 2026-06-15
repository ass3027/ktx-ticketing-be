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
        { headers: { 'Content-Type': 'application/json' } }
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
            tags: { type: 'confirm' },
        }
    );
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
