package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Brings this server's evidence folders in line with PostgreSQL. One pass removes the folders of erased copies, the
 * folders of ended attempts and of removed jobs, and the recorded inputs of attempts that no longer run. A pass runs
 * every {@link #INTERVAL} and on each erasure's wake-up, so an erased copy leaves every running server within
 * {@link #ERASURE_BOUND}. A server that is gone took its folders with its Pod's volume.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Server-local cleanup rechecks each workspace-scoped job and attempt owner before removal")
public class EvidenceReconciler {
    private static final Logger log = LoggerFactory.getLogger(EvidenceReconciler.class);

    public static final Duration INTERVAL = Duration.ofSeconds(30);

    /**
     * The time from an erasure's commit until no running server holds the erased copy: one interval and one pass, and
     * the stop of a sandbox whose job the erasure cancelled. The privacy documentation states it.
     */
    public static final Duration ERASURE_BOUND = Duration.ofMinutes(2);

    private final JobEvidenceFiles files;
    private final EvidenceFolderPersonDataCatalog copies;
    private final AttemptInputs inputs;
    private final Clock clock;
    private final AtomicLong lastSuccess = new AtomicLong();
    private final Counter failures;

    public EvidenceReconciler(
            JobEvidenceFiles files,
            EvidenceFolderPersonDataCatalog copies,
            AttemptInputs inputs,
            Clock clock,
            MeterRegistry meters) {
        this.files = files;
        this.copies = copies;
        this.inputs = inputs;
        this.clock = clock;
        Gauge.builder(AgentMetrics.EVIDENCE_RECONCILE_LAST_SUCCESS, lastSuccess, AtomicLong::get)
                .description("When this server last brought its evidence folders in line with PostgreSQL")
                .baseUnit("seconds")
                .register(meters);
        failures = Counter.builder(AgentMetrics.EVIDENCE_RECONCILE_FAILURES)
                .description("Evidence folders that a reconcile pass could not remove, and passes that failed")
                .register(meters);
    }

    /**
     * One pass. The timer and the erasure wake-up never run two passes at once. A pass succeeds only when no erased
     * or deleted job's folder remains on this server; each one that remains counts as a failure, and the last success
     * stays where it was, so the stalled-reconcile alert sees an erasure that does not reach this server.
     */
    public synchronized void reconcile() {
        try {
            int remaining = copies.removeErasedFolders().remaining() + files.cleanEndedAttempts();
            inputs.removeEnded();
            if (remaining > 0) {
                failures.increment(remaining);
                log.warn("Evidence reconcile left folders for the next pass: count={}", remaining);
                return;
            }
            lastSuccess.set(clock.instant().getEpochSecond());
        } catch (RuntimeException failure) {
            failures.increment();
            log.warn("Evidence reconcile failed: error={}", failure.getClass().getSimpleName(), failure);
        }
    }
}
