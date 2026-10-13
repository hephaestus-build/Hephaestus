package de.tum.cit.aet.hephaestus.integration.core.sync;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Mutable {@link Clock} test double — advances only when {@link #advance(Duration)} is called, so time windows can be
 * exercised deterministically without sleeping real time. Safe to read from another thread.
 */
public final class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public static MutableClock atFixedInstant() {
        return new MutableClock(Instant.parse("2026-07-14T10:00:00Z"), ZoneId.of("UTC"));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }
}
