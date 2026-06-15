package com.ktx.ticketing.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 테스트 전용 가변 Clock. {@link #advance(Duration)}로 시각을 앞당겨
 * HELD TTL 만료처럼 실시간 대기 없이 시간 경과를 시뮬레이션한다.
 */
public class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant start, ZoneId zone) {
        this.instant = start;
        this.zone = zone;
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    public void reset(Instant newInstant) {
        instant = newInstant;
    }

    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId z) { return new MutableClock(instant, z); }
    @Override public Instant instant() { return instant; }
}
