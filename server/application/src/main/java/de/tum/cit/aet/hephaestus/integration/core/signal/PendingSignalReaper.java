package de.tum.cit.aet.hephaestus.integration.core.signal;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-offers signals whose review was refused by something an operator can undo.
 *
 * <p>Without this the ledger's unique constraint would be a trap: a signal refused because the
 * workspace was momentarily inactive, or its budget was spent, is recorded — and a recorded signal is
 * never acted on twice. The review would be lost permanently, and nothing upstream would ever notice
 * because the provider has no further transition to announce.
 *
 * <p>Whether a blocker has cleared is not observable from here — there is no event for "an admin
 * re-enabled practices" — so the sweep simply re-attempts on a human timescale and lets the
 * submission path answer.
 */
@ConditionalOnServerRole
@Component
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
@WorkspaceAgnostic("Refused signals are swept for every workspace on the instance")
public class PendingSignalReaper {

    private static final Logger log = LoggerFactory.getLogger(PendingSignalReaper.class);

    private final ArtifactSignalRepository repository;
    private final SignalLedgerProperties properties;
    private final Map<ArtifactKind, PendingSignalResubmitter> resubmitters;

    public PendingSignalReaper(
            ArtifactSignalRepository repository,
            SignalLedgerProperties properties,
            List<PendingSignalResubmitter> resubmitterList) {
        this.repository = repository;
        this.properties = properties;
        Map<ArtifactKind, PendingSignalResubmitter> byKind = new HashMap<>();
        for (PendingSignalResubmitter resubmitter : resubmitterList) {
            byKind.put(resubmitter.artifactKind(), resubmitter);
        }
        this.resubmitters = Map.copyOf(byKind);
    }

    /**
     * Held under a lock rather than made idempotent: a resubmission that two replicas both attempt
     * would be settled by the job idempotency key, but only after both have paid for the work of
     * getting there.
     */
    @Scheduled(fixedDelay = 15, initialDelay = 5, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "pending-signal-reaper", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void sweep() {
        Instant now = Instant.now();

        int lapsed = repository.lapseStalePending(now.minus(properties.pendingLapseAfter()), now);
        if (lapsed > 0) {
            log.info("Lapsed pending signals past the retry deadline: count={}", lapsed);
        }

        List<ArtifactSignal> due = repository.findRetryablePending(
                now.minus(properties.pendingRetryAfter()), PageRequest.ofSize(properties.sweepBatchSize()));
        if (due.isEmpty()) {
            return;
        }

        repository.claimPendingForRetry(due.stream().map(ArtifactSignal::getId).toList(), now);

        for (ArtifactSignal signal : due) {
            try {
                // Inside the try: an unparseable kind must not abort the sweep for rows behind it.
                PendingSignalResubmitter resubmitter = resubmitters.get(ArtifactKind.of(signal.getArtifactKind()));
                if (resubmitter == null) {
                    // Leave it to the lapse deadline rather than burn a signal nothing here can act on.
                    log.debug("No resubmitter for pending signal kind, leaving it: kind={}", signal.getArtifactKind());
                    continue;
                }
                resubmitter.resubmit(signal);
            } catch (RuntimeException e) {
                log.warn(
                        "Failed to re-offer pending signal: signalId={}, signal={}, artifactId={}, kind={}",
                        signal.getId(),
                        signal.getSignalName(),
                        signal.getArtifactId(),
                        signal.getArtifactKind(),
                        e);
            }
        }
    }
}
