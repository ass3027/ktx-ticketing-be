-- B-2: 좌석 재예매 불가 버그 수정 (A-2 = 활성 한정 부분 유니크).
--
-- 문제: reservation.seat_inventory_id 의 "상태-무관 전역 유니크"(V1 의 UKqjf4pl71kkc4ifucnr5ycpnbf)는
--       좌석당 reservation 행을 영원히 1건으로 강제한다. 취소/만료는 행을 지우지 않고 상태만 바꾸므로,
--       되돌아온 좌석을 다시 예매하면 Duplicate entry → 500.
--
-- 의도한 불변식: "한 좌석에 동시에 '활성(HELD/CONFIRMED)'인 예약은 1건." 취소/만료 과거 예약은 제외.
--
-- MySQL 은 부분 인덱스(WHERE …)가 없으므로 생성 컬럼(generated column)으로 우회한다:
--   활성일 때만 seat_inventory_id 를, 아니면 NULL 을 갖는 파생 컬럼을 만들고 그 위에 유니크를 건다.
--   MySQL 유니크 인덱스는 NULL 중복을 허용하므로 → 활성은 좌석당 1건 강제(오버셀 DB 최후 방어선 유지),
--   취소/만료(NULL)는 공존 허용(재예매 가능).
-- STORED: 인덱스 대상이며 조회 부하 경로(예매)에서 매 평가를 피하려 디스크에 물리화.

ALTER TABLE reservation
    ADD COLUMN active_seat_inventory_id BIGINT
        AS (IF(status IN ('HELD','CONFIRMED'), seat_inventory_id, NULL)) STORED;

ALTER TABLE reservation
    ADD CONSTRAINT uk_active_seat UNIQUE (active_seat_inventory_id);

-- 기존 전역 유니크 제거 — 이게 버그의 원인. (이름은 V1 에서 고정해 둠)
-- 단, seat_inventory_id FK(FKb0pw74…)가 이 유니크 인덱스를 색인으로 쓰고 있어 그대로 DROP 하면
-- "Cannot drop index: needed in a foreign key constraint"(errno 1553). FK 가 쓸 일반 인덱스를
-- 먼저 만들어 두고 유니크를 제거한다(FK 는 컬럼 선두 인덱스 1개면 충분).
CREATE INDEX idx_reservation_seat_inventory ON reservation (seat_inventory_id);

ALTER TABLE reservation
    DROP INDEX UKqjf4pl71kkc4ifucnr5ycpnbf;
