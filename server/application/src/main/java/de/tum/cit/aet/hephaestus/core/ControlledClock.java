package de.tum.cit.aet.hephaestus.core;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A scenario clock. All zone views read the same instant. */
public final class ControlledClock extends Clock {

    private final AtomicReference<Instant> time;
    private final ZoneId zone;

    public ControlledClock(Instant initial) {
        this(new AtomicReference<>(initial), ZoneOffset.UTC);
    }

    private ControlledClock(AtomicReference<Instant> time, ZoneId zone) {
        this.time = time;
        this.zone = zone;
    }

    public void set(Instant instant) {
        time.set(instant);
    }

    /** Keeps infrastructure that admits the scenario driver on wall time. */
    public static Clock operationalClock(Clock applicationClock) {
        return applicationClock instanceof ControlledClock ? Clock.systemUTC() : applicationClock;
    }

    @Override
    public Instant instant() {
        return time.get();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this.zone.equals(zone) ? this : new ControlledClock(time, zone);
    }
}
