# 부하 테스트 실행 가이드

## 전제조건

- k6 로컬 설치: https://grafana.com/docs/k6/latest/set-up/install-k6/
- Docker Compose 앱 기동 중: `docker compose up --build`
- MySQL, Redis 접근 가능

## 실행 전 준비

```bash
# DB/Redis 초기화 후 앱 재기동 → DataInitializer 자동 재시드
make reset-seed
docker compose restart app
```

## 시나리오 실행

```bash
make run-L1    # 정합성 검증 (가장 중요, 항상 먼저 실행)
make run-L2    # 정상 혼합 부하 (p95/TPS 기준선)
make run-L2b   # AUTO 배정 처리량
make run-L3    # 조회 폭주 (5,000 VUser)
make run-L4    # 입장 제어 초과 부하
make run-L5    # 임계점 탐색 (계단식 증가)
make run-L6    # 지속 부하 30분 (누수 확인)
```

k6 내장 대시보드: http://localhost:5665

## 실험 Before/After

| 실험 | Before | After |
|------|--------|-------|
| E1 선점 제어 | `make run-E1-before` | `make run-E1-after` |
| E2 입장 제어 | `make run-E2-before` | `make run-E2-after` |
| E3 조회 캐시 | `make run-E3-before` | `make run-E3-after` |

E1/E3 Before 실행 전: 앱에 토글 환경변수 필요 (T4-9 구현 후 확인)

## 합격 기준

| 시나리오 | 지표 | 기준 |
|----------|------|------|
| L1 | oversell | == 0 |
| L1 | reserve_ok | == 1 |
| L2 | 예매 p95 | ≤ 500ms |
| L2 | 예매 p99 | ≤ 1,000ms |
| L2 | 조회 p95 | ≤ 200ms |
| L2 | 5xx 율 | < 1% |
| L2 | TPS | ≥ 200 |
| L3 | 조회 p95 | ≤ 200ms |
| L4 | 5xx 율 | < 1% (429/503 제외) |

## 부하 후 정합성 확인

```bash
mysql -h127.0.0.1 -uktx -pktx1234 ktx_ticketing
```

`load-tests/verify/post_run_check.sql` 의 쿼리를 실행해 DB 정합성을 수동 확인한다.

## 환경변수

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `BASE_URL` | `http://localhost:8080` | 앱 주소 |
| `SCHEDULE_ID` | `1` | L1 대상 스케줄 ID |
| `SEAT_INVENTORY_ID` | `1` | L1 인기 좌석 재고 ID |

```bash
# 예: 특정 좌석 ID 지정
SCHEDULE_ID=5 SEAT_INVENTORY_ID=42 make run-L1
```
