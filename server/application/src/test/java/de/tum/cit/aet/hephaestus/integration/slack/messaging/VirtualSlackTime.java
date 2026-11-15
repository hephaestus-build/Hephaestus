package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Virtual time for a throttle budget: a wait returns at once, moves the time by its length and is recorded. Like
 * {@link Thread#sleep}, a wait of an interrupted thread throws and clears the flag.
 */
final class VirtualSlackTime implements SlackTime {

    private final AtomicLong nanos = new AtomicLong();
    private final List<Long> waits = new CopyOnWriteArrayList<>();

    @Override
    public long nanoTime() {
        return nanos.get();
    }

    @Override
    public void sleep(long millis) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        waits.add(millis);
        advance(millis);
    }

    /** Moves the time by {@code millis}, as a call that took that long would. */
    void advance(long millis) {
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    /** The waits taken so far, in ms. */
    List<Long> waits() {
        return List.copyOf(waits);
    }
}
