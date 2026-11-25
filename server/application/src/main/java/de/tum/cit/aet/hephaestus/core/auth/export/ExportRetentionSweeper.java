package de.tum.cit.aet.hephaestus.core.auth.export;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Job;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Outcome;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly sweep that enforces the {@link ExportGenerationWorker#RETENTION} window: READY exports past
 * {@code expires_at} flip to EXPIRED and have their payload nulled.
 *
 * <p>Each call into {@link AccountExportService} commits one bounded batch, so an export that another transaction holds
 * is skipped, not waited for, and a failure keeps the batches before it. A run calls again while a batch is full, for
 * at most {@link #BUDGET}; what it leaves, and the exports it skipped, the next run expires.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("Pruning account-scoped, workspace-agnostic export rows")
public class ExportRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(ExportRetentionSweeper.class);

    /** The exports that one call of the owner function expires at most: its {@code LIMIT}. */
    static final int BATCH = 100;

    /**
     * A run starts no batch after this. With {@link #BATCH_TIMEOUT_SECONDS} for the last batch, it ends inside {@code
     * lockAtMostFor}, so no other replica overlaps it.
     */
    static final Duration BUDGET = Duration.ofMinutes(4);

    /** The longest one batch's transaction may take. */
    static final int BATCH_TIMEOUT_SECONDS = 30;

    private final AccountExportService accountExportService;
    private final PrivacyJobMetrics metrics;
    private final Clock clock;

    public ExportRetentionSweeper(AccountExportService accountExportService, PrivacyJobMetrics metrics, Clock clock) {
        this.accountExportService = accountExportService;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "account-export-retention-sweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void sweep() {
        Instant deadline = clock.instant().plus(BUDGET);
        int expired = 0;
        try {
            int batch;
            do {
                batch = accountExportService.expireRetention();
                expired += batch;
            } while (batch == BATCH && clock.instant().isBefore(deadline));
            // A full last batch means that due exports may remain for the next run.
            metrics.record(Job.EXPORT_RETENTION, batch == BATCH ? Outcome.INCOMPLETE : Outcome.SUCCESS);
            metrics.recordAffected(Job.EXPORT_RETENTION, expired);
            if (expired > 0) {
                log.info("auth.export: expired {} READY export(s) past retention", expired);
            }
        } catch (RuntimeException e) {
            // The batches before the failure are committed.
            metrics.recordAffected(Job.EXPORT_RETENTION, expired);
            metrics.record(Job.EXPORT_RETENTION, Outcome.FAILURE);
            throw e;
        }
    }
}
