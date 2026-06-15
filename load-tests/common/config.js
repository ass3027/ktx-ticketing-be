export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// L1 집중 타격 대상 — 시드 데이터 기준 첫 번째 스케줄의 첫 번째 좌석 재고 ID
// reset 후 실제 ID를 확인해 환경변수로 주입: SCHEDULE_ID=1 SEAT_INVENTORY_ID=1
export const SCHEDULE_ID = parseInt(__ENV.SCHEDULE_ID || '1');
export const SEAT_INVENTORY_ID = parseInt(__ENV.SEAT_INVENTORY_ID || '1');

export const USER_COUNT = 10_000;

// 조회 파라미터 (시드 데이터 고정값)
export const DEP = '서울';
export const ARR = '부산';
export const FROM_DATE = '2026-07-01T08:00:00';
