package com.ktx.ticketing.infra;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    private static final int BATCH_SIZE = 1000;
    private static final int INVENTORY_BATCH_SIZE = 5000;
    private static final int TOTAL_CARS = 20;
    private static final int SEATS_PER_CAR = 50;
    private static final int TOTAL_SCHEDULES = 50;
    private static final int TOTAL_USERS = 10_000;

    @Override
    public void run(ApplicationArguments args) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM train", Long.class);
        if (count != null && count > 0) {
            log.info("Seed data already exists (train={}), skipping", count);
            return;
        }

        log.info("Inserting seed data...");
        long start = System.currentTimeMillis();

        jdbc.update("INSERT INTO train(name, train_number) VALUES (?, ?)", "KTX 경부선", "KTX-001");
        Long trainId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        seedSeats(trainId);
        seedSchedules(trainId);
        seedSeatInventory(trainId);
        seedUsers();

        log.info("Seed data ready in {}ms  [train=1, seat={}, schedule={}, seat_inventory={}, user={}]",
            System.currentTimeMillis() - start,
            TOTAL_CARS * SEATS_PER_CAR,
            TOTAL_SCHEDULES,
            (long) TOTAL_CARS * SEATS_PER_CAR * TOTAL_SCHEDULES,
            TOTAL_USERS
        );
    }

    private void seedSeats(Long trainId) {
        List<Object[]> batch = new ArrayList<>(TOTAL_CARS * SEATS_PER_CAR);
        for (int car = 1; car <= TOTAL_CARS; car++) {
            for (int n = 1; n <= SEATS_PER_CAR; n++) {
                String sn = n + switch (n % 4) {
                    case 1 -> "A";
                    case 2 -> "B";
                    case 3 -> "C";
                    default -> "D";
                };
                batch.add(new Object[]{trainId, car, sn});
            }
        }
        jdbc.batchUpdate("INSERT INTO seat(train_id, car_number, seat_number) VALUES (?,?,?)", batch);
    }

    private void seedSchedules(Long trainId) {
        List<Object[]> batch = new ArrayList<>(TOTAL_SCHEDULES);
        LocalDateTime base = LocalDateTime.of(2026, 7, 1, 8, 0);
        for (int i = 0; i < TOTAL_SCHEDULES; i++) {
            LocalDateTime dep = base.plusDays(i);
            batch.add(new Object[]{trainId, "서울", "부산", dep, dep.plusHours(2).plusMinutes(30), TOTAL_CARS * SEATS_PER_CAR});
        }
        jdbc.batchUpdate(
            "INSERT INTO schedule(train_id, departure_station, arrival_station, departure_time, arrival_time, total_seats) VALUES (?,?,?,?,?,?)",
            batch
        );
    }

    private void seedSeatInventory(Long trainId) {
        List<Long> seatIds = jdbc.queryForList(
            "SELECT id FROM seat WHERE train_id = ? ORDER BY id", Long.class, trainId);
        List<Long> scheduleIds = jdbc.queryForList(
            "SELECT id FROM schedule WHERE train_id = ? ORDER BY id", Long.class, trainId);

        List<Object[]> batch = new ArrayList<>(INVENTORY_BATCH_SIZE);
        for (Long schedId : scheduleIds) {
            for (Long seatId : seatIds) {
                batch.add(new Object[]{schedId, seatId, "AVAILABLE", 0});
                if (batch.size() == INVENTORY_BATCH_SIZE) {
                    jdbc.batchUpdate(
                        "INSERT INTO seat_inventory(schedule_id, seat_id, status, version) VALUES (?,?,?,?)", batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate(
                "INSERT INTO seat_inventory(schedule_id, seat_id, status, version) VALUES (?,?,?,?)", batch);
        }
    }

    private void seedUsers() {
        LocalDateTime now = LocalDateTime.now();
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        for (int i = 1; i <= TOTAL_USERS; i++) {
            batch.add(new Object[]{"user" + i + "@ktx.test", "사용자" + i, now});
            if (batch.size() == BATCH_SIZE) {
                jdbc.batchUpdate("INSERT INTO users(email, name, created_at) VALUES (?,?,?)", batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO users(email, name, created_at) VALUES (?,?,?)", batch);
        }
    }
}
