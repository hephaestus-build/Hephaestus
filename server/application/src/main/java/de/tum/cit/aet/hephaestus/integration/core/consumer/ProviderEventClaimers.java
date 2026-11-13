package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnBackgroundWork;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The threads that claim and handle this server's provider events ({@link ProviderEventConsumer#claimAndHandle}). An
 * idle claimer looks again when this server queues an event ({@link ProviderEventNotifications}), and at least every
 * {@link #IDLE_POLL}.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnBackgroundWork
public class ProviderEventClaimers implements SmartLifecycle {

    /** How long an idle claimer waits for a notification before it looks again. */
    static final Duration IDLE_POLL = Duration.ofSeconds(5);

    /** How long a stopping server waits for running handlers before it interrupts them. */
    public static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    /** The part of {@link #SHUTDOWN_WAIT} that an interrupted claimer has to stop its handler and give its lease back. */
    static final Duration SHUTDOWN_RELEASE = Duration.ofSeconds(4);

    private final ProviderEventConsumer consumer;
    private final int count;
    private final Object idle = new Object();
    private final AtomicBoolean running = new AtomicBoolean();
    private final List<Thread> claimers = new CopyOnWriteArrayList<>();

    ProviderEventClaimers(
            ProviderEventConsumer consumer,
            ProviderEventNotifications notifications,
            ProviderEventConsumerProperties properties) {
        this.consumer = consumer;
        this.count = properties.claimers();
        notifications.subscribe(this::wake);
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        claimers.clear();
        for (int i = 0; i < count; i++) {
            claimers.add(Thread.ofPlatform().name("provider-event-claimer-" + i).start(this::claimLoop));
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        consumer.stopping();
        wake();
        // A handler gets the first part of the wait to end. Then its claimer is interrupted and gets the rest to stop
        // it and give its lease back, before the context closes the pool that the release needs.
        long deadline = System.nanoTime() + SHUTDOWN_WAIT.toNanos();
        long interruptAt = deadline - SHUTDOWN_RELEASE.toNanos();
        try {
            for (Thread claimer : claimers) {
                if (!claimer.join(Duration.ofNanos(Math.max(1, interruptAt - System.nanoTime())))) claimer.interrupt();
            }
            for (Thread claimer : claimers) {
                claimer.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** The claimer threads that are alive. */
    int alive() {
        return (int) claimers.stream().filter(Thread::isAlive).count();
    }

    private void wake() {
        synchronized (idle) {
            idle.notifyAll();
        }
    }

    private void claimLoop() {
        while (running.get()) {
            if (consumer.claimAndHandle()) continue;
            synchronized (idle) {
                if (!running.get()) return;
                try {
                    idle.wait(IDLE_POLL.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
