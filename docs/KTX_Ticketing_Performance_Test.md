# KTX 예매 시스템 — 성능 테스트 시나리오

> 상위 문서: `KTX_Ticketing_Architecture_and_Verification_Goals.md`(SLO/실험 정의)
> 목적: SLO를 **재현 가능한 부하 테스트**로 측정. 영상 핵심 — "기준을 설계하고 직접 측정". 숫자 크기보다 *왜 그 기준 + Before/After*.
> 도구: **k6**(권장, JS 시나리오) 또는 **nGrinder**(웹 UI).

---

## 0. 사전 준비 (모든 테스트 공통)

### 0.1 테스트 데이터
| 항목 | 값(예시) | 비고 |
|------|----------|------|
| 운행편(Schedule) | 50편 | 조회 부하 다양화 |
| 편당 좌석 | 1,000석 (총 50,000석) | 매진/임계 측정용 |
| 인기 좌석(직접 선택 경합용) | 특정 좌석 1개 | L1에서 집중 타격 |
| 사용자(User) | 10,000명 (토큰 발급용) | 중복 예매 방지 검증 |

### 0.2 환경 원칙
- **격리**: 부하 생성기와 서버를 분리(생성기 부하가 결과 오염 방지)
- **고정**: 매 테스트 전 DB/Redis 동일 상태로 **리셋**(seed 스크립트)
- **워밍업**: 본 측정 전 30초~1분 저부하로 JIT/커넥션풀/캐시 예열
- **반복**: 각 시나리오 **3회 이상** 측정 → 중앙값 사용(변동성 확인)

### 0.3 측정 대상 (k6 + 서버 모니터링 동시 수집)
- 클라이언트(k6): 응답시간 p50/p95/p99, RPS, 에러율, 체크 통과율
- 서버: CPU / 메모리 / DB 커넥션 사용률 / Redis 명령 지연 / GC
- DB: 슬로우 쿼리, 락 대기

---

## 1. 시나리오 정의

> 부하 단위 = VUser(가상 사용자). ramp-up으로 **점진 증가** 필수.

### L1. 직접 선택 — 단일 좌석 동시 경쟁 (정합성)
| 항목 | 내용 |
|------|------|
| 목적 | 같은 좌석에 N명 → **1명만 성공, 초과 0건** (S4) |
| 프로파일 | 1,000 VUser를 **동시 도착**(거의 spike), 각자 `mode=SEAT(인기좌석)` 1회 |
| 검증 | 성공 응답 정확히 1, 나머지 "이미 선택됨". 부하 후 DB CONFIRMED=1 |
| 합격 | 초과 판매 0건, 중복 예매 0건 |

### L2. 정상 예매 플로우 (혼합)
| 항목 | 내용 |
|------|------|
| 목적 | 현실적 혼합 부하에서 응답/처리량 (S1, S3) |
| 프로파일 | 0 → 1,000 VUser **ramp-up 2분** → 1,000 유지 5분 → ramp-down 1분 |
| 요청 구성 | 조회 70% / 입장+예매(SEAT 50%·AUTO 50%) 25% / 확정 5% |
| 합격 | 예매 p95 ≤ 500ms, ≥ 200 TPS, 5xx < 1% |

### L2b. 자동 배정 처리량
| 항목 | 내용 |
|------|------|
| 목적 | 경합 분산 시 최대 처리량 (직접 선택 대비) |
| 프로파일 | 1,000 VUser가 `mode=AUTO`로 동일 편 좌석 소진까지 |
| 검증 | 좌석 수만큼만 성공, 이후 매진. 처리량을 L1과 **비교 그래프** |

### L3. 운행 조회 폭주 (읽기 부하)
| 항목 | 내용 |
|------|------|
| 목적 | 읽기 경로 응답·캐시 효과 (S2), 실험 E3 |
| 프로파일 | 0 → 5,000 VUser ramp-up 1분, 리스트/매진 조회 반복 |
| 합격 | 조회 p95 ≤ 200ms, 표시 staleness ≤ 2초 |

### L4. 입장 초과 부하
| 항목 | 내용 |
|------|------|
| 목적 | 활성자 상한 K 초과 시 **입장 제어 정상 동작** (S5), 실험 E2 |
| 프로파일 | K의 3~5배 VUser가 입장+예매 시도 |
| 합격 | 초과분은 429/503 + Retry-After로 흡수, 코어 예매 경로 p95 유지, 5xx(서버오류) < 1% |

### L5. 임계점 탐색 (Stress)
| 항목 | 내용 |
|------|------|
| 목적 | **무너지는 동시 사용자 수**를 숫자로 (S6) |
| 프로파일 | VUser를 한계까지 계단식 증가(500→1k→2k→4k…), 단계마다 유지 |
| 산출 | p99 급증/에러율 급증/처리량 꺾이는 지점 = 임계점 |

### L6. 지속 부하 (Soak)
| 항목 | 내용 |
|------|------|
| 목적 | 누수/누적 문제 (메모리, 락, HELD 만료) |
| 프로파일 | 중간 부하(예 300 VUser) **30분 유지** |
| 합격 | 응답시간 우상향 없음, HELD 만료 후 좌석/카운터 정상 복구 |

---

## 2. k6 스크립트 골격 (의사코드)

```javascript
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const oversell = new Counter('oversell');      // 초과 판매(있으면 안 됨)
const reserveOk = new Counter('reserve_ok');

export const options = {
  scenarios: {
    L2_normal: {
      executor: 'ramping-vus',
      stages: [
        { duration: '2m', target: 1000 },  // ramp-up
        { duration: '5m', target: 1000 },  // sustain
        { duration: '1m', target: 0 },     // ramp-down
      ],
    },
  },
  thresholds: {
    'http_req_duration{type:reserve}': ['p(95)<500', 'p(99)<1000'],
    'http_req_duration{type:list}':    ['p(95)<200'],
    'http_req_failed':                 ['rate<0.01'],   // 5xx<1%
    'oversell':                        ['count==0'],     // 정합성
  },
};

export default function () {
  // 1) 조회
  http.get(`${BASE}/api/schedules?dep=서울&arr=부산&date=2026-06-30`, { tags:{type:'list'} });

  // 2) 입장 토큰
  const entry = http.post(`${BASE}/api/entry`, ...);
  if (entry.status === 429 || entry.status === 503) return; // 입장 제어 정상

  // 3) 예매 (SEAT or AUTO 분기)
  const res = http.post(`${BASE}/api/reservations`,
    JSON.stringify({ mode: Math.random()<0.5 ? 'SEAT':'AUTO', seatId, token }),
    { tags:{type:'reserve'} });
  check(res, { 'reserved': r => r.status === 200 }) && reserveOk.add(1);

  // 4) 확정 (일부만)
}
```
> nGrinder 사용 시: 동일 시나리오를 Groovy 스크립트 + 웹 UI에서 VUser/ramp 설정. 코드 없이 시작 쉬움.

---

## 3. 정합성 검증 (부하 후 실행 — 가장 중요)

부하가 끝난 뒤 DB/Redis를 직접 질의해 **수치로** 확인.

- [ ] `SELECT COUNT(*) FROM reservation WHERE status='CONFIRMED' AND schedule_id=?` ≤ 총 좌석 수
- [ ] 동일 좌석에 CONFIRMED 2건 이상 **없음**
- [ ] 동일 user 중복 예매 **없음**
- [ ] HELD 만료 후 좌석 AVAILABLE 복구, `avail` Set 크기 == DB AVAILABLE 수
- [ ] Redis 잔여 카운터가 DB 실제 잔여로 수렴
- [ ] **매진 표시인데 예매 성공 = 0건**(매진 표시는 보수적)

---

## 4. Before / After 실험 매핑

| 실험 | Before (끄고 측정) | After (켜고 측정) | 보여줄 그래프 |
|------|-------------------|-------------------|---------------|
| **E1 동시성 제어** | 선점/락 제거 | SREM 선점(or 분산 락) | 초과판매 N → **0**, (가)SREM vs (나)락 처리량 비교 |
| **E2 입장 제어** | 제어 off | 활성자 상한 K on | 5xx율·p99 폭증 → 안정화 |
| **E3 조회 캐시** | 매 요청 DB 집계 | Redis 카운터/캐시 | 조회 p95↓, DB CPU↓ |
| (E4 선택) A vs B | Redis 동기 | MQ 비동기 | 처리량/지연/정합성 비교 |
| **E5 가상 스레드** | 플랫폼 스레드(`virtual.enabled=false`) | 가상 스레드 on | 동일 부하(L2)에서 처리량↑·p95/p99↓, 스레드 점유·풀 포화 비교 |

> 각 실험: **동일 부하 프로파일**로 Before/After만 바꿔 측정 → 차이를 그래프 1장 + 한 줄 해석.

---

## 5. 결과 기록 템플릿

| 시나리오 | 일시 | VUser | p50 | p95 | p99 | RPS/TPS | 에러율 | 임계점 | 정합성 | 합격? | 메모 |
|----------|------|-------|-----|-----|-----|---------|--------|--------|--------|-------|------|
| L1 | | 1000 | | | | | | - | 초과0 | | |
| L2 | | 1000 | | | | | | - | | | |
| L2b | | 1000 | | | | | | - | 매진수렴 | | AUTO, L1 대비 비교 |
| L3 | | 5000 | | | | | | - | - | | |
| L4 | | 3xK | | | | | | - | - | | |
| L5 | | 계단 | | | | | | ___명 | - | | |
| L6 | | 300 | | | | | | - | | | |

> README/영상에는 이 표 + Before/After 그래프 + "왜 이 기준값인지" 한 단락을 싣는다.

---

## 6. 실행 순서(권장)
1. seed 스크립트로 데이터 리셋
2. L1(정합성) → 빠르게 핵심 안전성 확인
3. L2/L2b(처리량) → 기준선(baseline) 확보
4. L3(조회) + E3
5. L4(입장 제어) + E2
6. L5(임계점)로 K값 역산 → 설계 미정값 확정
7. L6(soak)
8. E1 Before/After로 마무리(하이라이트 그래프)
