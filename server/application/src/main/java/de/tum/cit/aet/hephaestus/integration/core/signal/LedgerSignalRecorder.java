package de.tum.cit.aet.hephaestus.integration.core.signal;

import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code artifact_signal} table as a {@link SignalRecorder}.
 *
 * <p>Every method demands the caller's transaction. A signal that was recorded but whose consequence
 * rolled back would be dedup for a review that never happened, so the record and the decision it
 * licenses must succeed or fail together.
 */
@Service
public class LedgerSignalRecorder implements SignalRecorder {

    private static final Logger log = LoggerFactory.getLogger(LedgerSignalRecorder.class);

    private final ArtifactSignalRepository repository;
    private final PracticeReviewRefusalMetrics refusalMetrics;
    private final MeterRegistry meterRegistry;

    public LedgerSignalRecorder(
            ArtifactSignalRepository repository,
            PracticeReviewRefusalMetrics refusalMetrics,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.refusalMetrics = refusalMetrics;
        this.meterRegistry = meterRegistry;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean record(
            SignalKey key, Instant occurredAt, DiscoveredVia discoveredVia, @Nullable Long requestedByUserId) {
        Instant now = Instant.now();
        // A reconciliation pass knows that something happened, not that it is the right one to act on it,
        // so it may only ever add a row; a live or requested observation may also take over one nobody
        // has decided yet. BACKFILL takes that second branch on purpose — a first sync leaves its rows
        // RECORDED-but-undecided so it fires nothing, and a confirmed campaign is what may claim them.
        boolean owned;
        if (discoveredVia == DiscoveredVia.SYNC) {
            // A sync notices what already happened; nobody asked it to. Attributing one to a requester
            // would let a person's request quota be spent by a background pass they never triggered.
            if (requestedByUserId != null) {
                throw new IllegalArgumentException("A sync-discovered signal has no requester to attribute it to");
            }
            int recorded = repository.insertIfAbsent(key, UUID.randomUUID(), occurredAt, discoveredVia.name(), now);
            owned = ownsSignal(recorded, key);
        } else {
            int affected = repository.insertOrClaimUndecided(
                    key, UUID.randomUUID(), occurredAt, discoveredVia.name(), now, requestedByUserId);
            owned = ownsSignal(affected, key);
        }
        meterRegistry
                .counter(
                        IntegrationCoreMetrics.REVIEW_OCCASIONS,
                        "signal",
                        key.signalName().value(),
                        "outcome",
                        owned ? "recorded" : "duplicate")
                .increment();
        return owned;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean defer(SignalKey key, Instant occurredAt, @Nullable Long actorUserId) {
        int affected = repository.insertDeferred(key, UUID.randomUUID(), occurredAt, Instant.now(), actorUserId);
        meterRegistry
                .counter(
                        IntegrationCoreMetrics.REVIEW_OCCASIONS,
                        "signal",
                        key.signalName().value(),
                        "outcome",
                        affected == 1 ? "deferred" : "duplicate")
                .increment();
        return affected == 1;
    }

    private boolean ownsSignal(int affected, SignalKey key) {
        if (affected == 0) {
            log.debug(
                    "Signal already settled, not acting: workspaceId={}, signal={}, artifactId={}, revision={}",
                    key.workspaceId(),
                    key.signalName(),
                    key.artifactId(),
                    key.revision());
        }
        return affected == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markTriggered(SignalKey key, UUID jobId) {
        int affected = repository.markTriggered(key, jobId, Instant.now());
        if (affected == 0) {
            logUnsettled("triggered", key);
            return;
        }
        log.debug(
                "Signal triggered: workspaceId={}, signal={}, artifactId={}, jobId={}",
                key.workspaceId(),
                key.signalName(),
                key.artifactId(),
                jobId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markRefused(SignalKey key, SignalStateReason reason) {
        int affected = repository.markRefused(key, reason.resultingState().name(), reason.name(), Instant.now());
        if (affected == 0) {
            logUnsettled("refused", key);
            return;
        }
        refusalMetrics.recordSubmissionRefusal(reason.name().toLowerCase(Locale.ROOT));
        log.debug(
                "Signal refused: workspaceId={}, signal={}, artifactId={}, reason={}, state={}",
                key.workspaceId(),
                key.signalName(),
                key.artifactId(),
                reason,
                reason.resultingState());
    }

    /**
     * A settle that matched nothing: the row is gone, or somebody already decided it. Either way this
     * caller's decision is void, and the ledger's purpose is that no signal ends up with no explanation.
     */
    private void logUnsettled(String attempted, SignalKey key) {
        log.warn(
                "Signal was not settleable as {}, leaving whoever decided it: workspaceId={}, signal={},"
                        + " artifactId={}, revision={}",
                attempted,
                key.workspaceId(),
                key.signalName(),
                key.artifactId(),
                key.revision());
    }
}
