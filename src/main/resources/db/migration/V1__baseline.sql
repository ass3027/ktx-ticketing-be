-- V1 baseline = 기존 Hibernate ddl-auto 가 생성하던 스키마 그대로(동작 동일성).
-- 출처: 현 엔티티에서 Hibernate schema-export(jakarta.persistence.schema-generation)로 추출.
-- 제약/FK/인덱스 이름은 Hibernate 자동 생성명을 보존해, 이미 ddl-auto 로 만들어진 기존 DB 와
-- ddl-auto: validate 가 충돌 없이 통과하도록 한다(이름 불일치 시 validate 실패).

create table train (
    id bigint not null auto_increment,
    name varchar(255) not null,
    train_number varchar(50) not null,
    primary key (id)
) engine=InnoDB;

create table seat (
    id bigint not null auto_increment,
    train_id bigint not null,
    car_number integer not null,
    seat_number varchar(10) not null,
    primary key (id)
) engine=InnoDB;

create table schedule (
    id bigint not null auto_increment,
    train_id bigint not null,
    departure_station varchar(50) not null,
    arrival_station varchar(50) not null,
    departure_time datetime(6) not null,
    arrival_time datetime(6) not null,
    total_seats integer not null,
    primary key (id)
) engine=InnoDB;

create table seat_inventory (
    id bigint not null auto_increment,
    schedule_id bigint not null,
    seat_id bigint not null,
    status enum ('AVAILABLE','HELD','SOLD') not null,
    version integer not null,
    held_at datetime(6),
    expires_at datetime(6),
    primary key (id)
) engine=InnoDB;

create table users (
    id bigint not null auto_increment,
    email varchar(255) not null,
    name varchar(100) not null,
    created_at datetime(6) not null,
    primary key (id)
) engine=InnoDB;

create table reservation (
    id bigint not null auto_increment,
    user_id bigint not null,
    seat_inventory_id bigint not null,
    status enum ('CANCELLED','CONFIRMED','EXPIRED','HELD') not null,
    held_at datetime(6) not null,
    expires_at datetime(6) not null,
    confirmed_at datetime(6),
    cancelled_at datetime(6),
    primary key (id)
) engine=InnoDB;

-- 유니크 제약 (Hibernate 자동 생성명 보존)
alter table train
    add constraint UK5mk8elq5bgy1fnyhfx1v4espy unique (train_number);

alter table seat
    add constraint UKbli0475lt03oywiw4ft2ynq0w unique (train_id, car_number, seat_number);

alter table seat_inventory
    add constraint UK32emsgkrd4lp9s57d4f4av308 unique (schedule_id, seat_id);

alter table users
    add constraint UK6dotkott2kjsp8vw4d0m25fb7 unique (email);

-- reservation.seat_inventory_id 전역 유니크 — B-2(좌석 재예매 버그)의 원인. V2 에서 부분 유니크로 교체.
alter table reservation
    add constraint UKqjf4pl71kkc4ifucnr5ycpnbf unique (seat_inventory_id);

-- 인덱스
create index idx_schedule_status
    on seat_inventory (schedule_id, status);

-- 외래키 (Hibernate 자동 생성명 보존)
alter table seat
    add constraint FKf06vp4yt7gfurnva97y7hlqa3
    foreign key (train_id) references train (id);

alter table schedule
    add constraint FK8x7cqx078595e7vsua30ekjl7
    foreign key (train_id) references train (id);

alter table seat_inventory
    add constraint FKe88do7283d9jvoiyb6ofp8vwl
    foreign key (schedule_id) references schedule (id);

alter table seat_inventory
    add constraint FKstchhekj08xr8ivij8svw7dm5
    foreign key (seat_id) references seat (id);

alter table reservation
    add constraint FKb0pw74xf7nl22uwb38s4sfroj
    foreign key (seat_inventory_id) references seat_inventory (id);

alter table reservation
    add constraint FKrea93581tgkq61mdl13hehami
    foreign key (user_id) references users (id);
