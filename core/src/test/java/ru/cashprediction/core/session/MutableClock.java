package ru.cashprediction.core.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Часы, которые двигает тест.
 */
final class MutableClock extends Clock {

    private volatile Instant instant;

    MutableClock(Instant start) {
        this.instant = start;
    }

    /**
     * Сдвигает часы.
     *
     * @param duration на сколько
     */
    void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
