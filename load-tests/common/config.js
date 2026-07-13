// 127.0.0.1 고정 — 'localhost' 는 Windows + Docker Desktop 에서 IPv6(::1) 로 먼저 해석돼
// Docker 포트포워딩(IPv4)을 못 만나 1,000 VU 중 다수가 connection refused(RST) 로 떨어진다.
// (Reset-Seed.ps1 health 체크와 동일한 이유.) 다른 호스트 대상은 BASE_URL 로 주입.
export const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';

// L1 집중 타격 대상 — 시드 데이터 기준 첫 번째 스케줄의 첫 번째 좌석 재고 ID
// reset 후 실제 ID를 확인해 환경변수로 주입: SCHEDULE_ID=1 SEAT_INVENTORY_ID=1
export const SCHEDULE_ID = parseInt(__ENV.SCHEDULE_ID || '1');
export const SEAT_INVENTORY_ID = parseInt(__ENV.SEAT_INVENTORY_ID || '1');

export const USER_COUNT = 10_000;

// 시드 스케줄 수(DataInitializer.TOTAL_SCHEDULES)와 일치해야 한다. L5 가 예매를 여러 스케줄에
// 분산해 단일 avail 키 매진을 피하는 데 쓴다. 시드 변경 시 같이 맞춘다.
export const SCHEDULE_COUNT = parseInt(__ENV.SCHEDULE_COUNT || '50');

// 조회 파라미터
export const DEP = '서울';
export const ARR = '부산';

// 시드 base(오늘 KST+1일 08:00, DataInitializer.seedSchedules 와 동일 규칙)와 일치(B-4).
// k6 컨테이너는 UTC(docker-compose.k6.yml TZ 미설정)라, epoch 에 +9h 를 더해 KST 달력일로
// 이동시킨 뒤 getUTC* 로 읽으면 "KST 벽시계" 숫자가 나온다. KST 는 DST 없어 +9h 고정이 항상 정확.
function seedFromDateKst() {
    const KST_OFFSET_MS = 9 * 3600 * 1000;
    const nowKst = new Date(Date.now() + KST_OFFSET_MS);
    const base = new Date(Date.UTC(
        nowKst.getUTCFullYear(), nowKst.getUTCMonth(), nowKst.getUTCDate() + 1, 8, 0, 0));
    return base.toISOString().slice(0, 19); // 'YYYY-MM-DDT08:00:00'
}
export const FROM_DATE = __ENV.FROM_DATE || seedFromDateKst();
