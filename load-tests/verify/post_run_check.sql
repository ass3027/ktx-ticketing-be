-- 부하 테스트 후 정합성 수동 검증 쿼리
-- 사용법: mysql -h127.0.0.1 -uktx -pktx1234 ktx_ticketing 으로 접속 후 실행
-- :schedule_id, :seat_inventory_id 는 테스트에 사용한 실제 ID 로 치환

-- ① 특정 좌석의 HELD/CONFIRMED 건 수 (L1: 1 이어야 함)
SELECT status, COUNT(*) AS cnt
FROM reservation
WHERE seat_inventory_id = :seat_inventory_id
GROUP BY status;

-- ② 동일 좌석 중복 예매 확인 (HELD 또는 CONFIRMED 이 2건 이상이면 이상)
SELECT seat_inventory_id, status, COUNT(*) AS cnt
FROM reservation
WHERE status IN ('HELD', 'CONFIRMED')
GROUP BY seat_inventory_id, status
HAVING COUNT(*) > 1;

-- ③ 스케줄 전체 CONFIRMED 건 수 ≤ 총 좌석 수 (1,000)
SELECT COUNT(*) AS total_confirmed
FROM reservation r
JOIN seat_inventory si ON r.seat_inventory_id = si.id
WHERE si.schedule_id = :schedule_id
  AND r.status = 'CONFIRMED';

-- ④ DB 잔여 AVAILABLE 좌석 수 (Redis SCARD 와 비교용)
SELECT COUNT(*) AS available_count
FROM seat_inventory
WHERE schedule_id = :schedule_id
  AND status = 'AVAILABLE';

-- ⑤ HELD 상태이지만 만료 시각 초과 (스케줄러 미처리 잔재 확인)
SELECT COUNT(*) AS expired_held_count
FROM reservation
WHERE status = 'HELD'
  AND expires_at < NOW();

-- ⑥ 동일 사용자 중복 예매 확인 (같은 스케줄에 HELD/CONFIRMED 2건 이상)
SELECT r.user_id, COUNT(*) AS cnt
FROM reservation r
JOIN seat_inventory si ON r.seat_inventory_id = si.id
WHERE si.schedule_id = :schedule_id
  AND r.status IN ('HELD', 'CONFIRMED')
GROUP BY r.user_id
HAVING COUNT(*) > 1;
