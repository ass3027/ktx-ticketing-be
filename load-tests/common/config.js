// 127.0.0.1 고정 — 'localhost' 는 Windows + Docker Desktop 에서 IPv6(::1) 로 먼저 해석돼
// Docker 포트포워딩(IPv4)을 못 만나 1,000 VU 중 다수가 connection refused(RST) 로 떨어진다.
// (Reset-Seed.ps1 health 체크와 동일한 이유.) 다른 호스트 대상은 BASE_URL 로 주입.
export const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';

// L1 집중 타격 대상 — 시드 데이터 기준 첫 번째 스케줄의 첫 번째 좌석 재고 ID
// reset 후 실제 ID를 확인해 환경변수로 주입: SCHEDULE_ID=1 SEAT_INVENTORY_ID=1
export const SCHEDULE_ID = parseInt(__ENV.SCHEDULE_ID || '1');
export const SEAT_INVENTORY_ID = parseInt(__ENV.SEAT_INVENTORY_ID || '1');

export const USER_COUNT = 10_000;

// 조회 파라미터 (시드 데이터 고정값)
export const DEP = '서울';
export const ARR = '부산';
export const FROM_DATE = '2026-07-01T08:00:00';
