-- 부하 테스트 전 DB 초기화 스크립트
-- FK 참조 역순으로 TRUNCATE (reservation → seat_inventory → schedule → seat → train → users)
-- DataInitializer 가 앱 기동 시 train COUNT == 0 이면 자동 재시드

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE reservation;
TRUNCATE TABLE seat_inventory;
TRUNCATE TABLE schedule;
TRUNCATE TABLE seat;
TRUNCATE TABLE train;
TRUNCATE TABLE users;
SET FOREIGN_KEY_CHECKS = 1;
