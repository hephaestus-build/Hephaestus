package de.tum.cit.aet.hephaestus.core.web;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Job;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Outcome;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerSweeps;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RowSweepRule;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes the create receipts of every owner after the retention period. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ClientInputExpiry {
    private static final int PRUNE_BATCH_SIZE = 1000;
    // A burst drains in one run instead of reporting each full batch as work left behind. After a full last batch, one
    // more batch runs: only when it is full too are receipts left behind. A run removes up to 13 batches.
    private static final int PRUNE_BATCHES_PER_RUN = 12;

    private final OwnerSweeps ownerSweeps;
    private final PrivacyJobMetrics metrics;
    private final Clock clock;

    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "client-input-cleanup", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void prune() {
        // Each batch commits on its own, so a failed batch keeps the batches before it.
        long removed = 0;
        try {
            int batch = PRUNE_BATCH_SIZE;
            for (int run = 0; run < PRUNE_BATCHES_PER_RUN && batch == PRUNE_BATCH_SIZE; run++) {
                batch = expireBatch();
                removed += batch;
            }
            int left = batch == PRUNE_BATCH_SIZE ? expireBatch() : 0;
            removed += left;
            metrics.record(Job.CLIENT_INPUT_RETENTION, left == PRUNE_BATCH_SIZE ? Outcome.INCOMPLETE : Outcome.SUCCESS);
            metrics.recordAffected(Job.CLIENT_INPUT_RETENTION, removed);
        } catch (RuntimeException exception) {
            metrics.recordAffected(Job.CLIENT_INPUT_RETENTION, removed);
            metrics.record(Job.CLIENT_INPUT_RETENTION, Outcome.FAILURE);
            throw exception;
        }
    }

    private int expireBatch() {
        return ownerSweeps.expire(RowSweepRule.CLIENT_INPUT_EXPIRY, clock.instant());
    }
}
