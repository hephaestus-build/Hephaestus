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
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Brings this server's evidence folders in line with PostgreSQL. One pass removes the folders of erased copies, the
 * folders of ended attempts and of removed jobs, and the recorded inputs of attempts that no longer run. A pass runs
 * every {@link #INTERVAL} and on each erasure's wake-up. A held lease or failed cleanup keeps a copy for a later pass.
 * No failed step prevents the other cleanups, and no incomplete pass advances the last-success signal.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Server-local cleanup rechecks each workspace-scoped job and attempt owner before removal")
public class EvidenceReconciler {
    private static final Logger log = LoggerFactory.getLogger(EvidenceReconciler.class);

    public static final Duration INTERVAL = Duration.ofSeconds(30);

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
                .description("Evidence copies that a reconcile pass could not remove, and cleanup steps that failed")
                .register(meters);
    }

    /**
     * One pass. The timer and the erasure wake-up never run two passes at once. A pass succeeds only when no erased
     * or deleted job's folder remains on this server; each one that remains counts as a failure, and the last success
     * stays where it was, so the stalled-reconcile alert sees an erasure that does not reach this server.
     */
    public synchronized void reconcile() {
        int remaining = step(() -> copies.removeErasedFolders().remaining())
                + step(files::cleanEndedAttempts)
                + step(() -> {
                    inputs.removeEnded();
                    return 0;
                });
        if (remaining > 0) {
            failures.increment(remaining);
            log.warn("Evidence reconcile left copies for the next pass: count={}", remaining);
            return;
        }
        lastSuccess.set(clock.instant().getEpochSecond());
    }

    private static int step(IntSupplier cleanup) {
        try {
            return cleanup.getAsInt();
        } catch (RuntimeException failure) {
            log.warn("Evidence reconcile failed: error={}", failure.getClass().getSimpleName(), failure);
            return 1;
        }
    }
}
