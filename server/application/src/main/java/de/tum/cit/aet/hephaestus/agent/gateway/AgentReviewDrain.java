package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.job.AgentProperties;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Stops new claims before waiting for durable review leases, with HTTP and the database still available. */
@Component
@ConditionalOnAgentRuntime
@Profile("!specs & !cds-training")
public class AgentReviewDrain implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(AgentReviewDrain.class);

    private final AgentRuns runs;
    private final AgentProperties properties;
    private final ApplicationAvailability availability;
    private final ReentrantReadWriteLock claims = new ReentrantReadWriteLock();
    private volatile boolean running;

    public AgentReviewDrain(AgentRuns runs, AgentProperties properties, ApplicationAvailability availability) {
        this.runs = runs;
        this.properties = properties;
        this.availability = availability;
    }

    /** A claim commits before stop can count leases; startup and draining never admit new work. */
    public <T> Optional<T> claim(Supplier<Optional<T>> claim) {
        claims.readLock().lock();
        try {
            if (!running || availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC)
                return Optional.empty();
            return claim.get();
        } finally {
            claims.readLock().unlock();
        }
    }

    @Override
    public void start() {
        claims.writeLock().lock();
        try {
            running = true;
        } finally {
            claims.writeLock().unlock();
        }
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void stop(Runnable callback) {
        try {
            claims.writeLock().lock();
            try {
                if (!running) return;
                running = false;
            } finally {
                claims.writeLock().unlock();
            }
            long deadline = System.nanoTime() + properties.drainTimeout().toNanos();
            int leased;
            while ((leased = runs.leasedReviews()) > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    log.atWarn()
                            .addKeyValue("event.name", "agent.review.drain.expired")
                            .addKeyValue("run.count", leased)
                            .log("Review drain expired; remaining runs recover through their leases");
                    return;
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(left, TimeUnit.SECONDS.toNanos(1)));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return AgentShutdownConfiguration.DRAIN_PHASE;
    }
}
