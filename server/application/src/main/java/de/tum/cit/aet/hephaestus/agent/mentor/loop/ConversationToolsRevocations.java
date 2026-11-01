package de.tum.cit.aet.hephaestus.agent.mentor.loop;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsJobs;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnAttempts;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Keeps only the tools Jobs of live attempts, each bound to the context that its attempt may read now. A Job goes when
 * the workspace's data changed (a person was erased, chose not to have AI read their work, or a repository was
 * hidden), when its attempt ended or lost its lease, and in any case within {@link #SWEEP}. Every worker does this
 * from the ledger alone, so no Job outlives a worker that crashed or the worker that started it.
 */
@Component
@ConditionalOnWorkerRole
@Profile(ConversationAssignments.PROFILES)
public final class ConversationToolsRevocations implements MentorLedgerNotifications.Listener {

    private static final Logger log = LoggerFactory.getLogger(ConversationToolsRevocations.class);

    /** The longest a Job of a changed context or an ended attempt can be left after a missed notification. */
    static final Duration SWEEP = Duration.ofSeconds(30);

    private final ToolsJobs tools;
    private final MentorTurnAttempts attempts;
    private final ScheduledExecutorService sweeps = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("tools-revocations").factory());

    /** Whether a pass is queued: it covers every change until it starts, so changes never queue up behind it. */
    private final AtomicBoolean queued = new AtomicBoolean();

    public ConversationToolsRevocations(
            ToolsJobs tools, MentorTurnAttempts attempts, MentorLedgerNotifications notifications) {
        this.tools = tools;
        this.attempts = attempts;
        notifications.subscribe(this);
        // retainLive logs its own failures, so the sweep's future carries none.
        var unused =
                sweeps.scheduleWithFixedDelay(this::retainLive, SWEEP.toSeconds(), SWEEP.toSeconds(), TimeUnit.SECONDS);
    }

    /** The attempt ended: its Job goes now. A Job whose attempt lost its lease goes with the next pass. */
    @Override
    public void turnChanged(MentorLedgerEvents.TurnChanged change) {
        if (change.status().isTerminal()) {
            revoke();
        }
    }

    @Override
    public void contextChanged(MentorLedgerEvents.ContextChanged change) {
        revoke();
    }

    /** A missed notification may have changed any context or ended any attempt. */
    @Override
    public void resync() {
        revoke();
    }

    @PreDestroy
    public void stop() {
        sweeps.shutdownNow();
    }

    /** Runs off the ledger's listener thread, which must return quickly. */
    private void revoke() {
        if (queued.compareAndSet(false, true)) {
            sweeps.execute(this::retainLive);
        }
    }

    private void retainLive() {
        // Cleared before the read, so a change during this pass queues the next one.
        queued.set(false);
        try {
            List<ToolsJobs.Binding> live = attempts.live().stream()
                    .map(attempt -> new ToolsJobs.Binding(
                            attempt.workspaceId(),
                            attempt.turnId(),
                            attempt.attempt(),
                            attempt.leaseId(),
                            attempt.contextGeneration()))
                    .toList();
            tools.retainOnly(live);
        } catch (RuntimeException e) {
            // The next notification or sweep tries again; each Job's own deadline still ends it.
            log.warn(
                    "Could not remove the tools Jobs of ended attempts: {}",
                    e.getClass().getSimpleName());
        }
    }
}
