package de.tum.cit.aet.hephaestus.core.auth.export;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Job;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Outcome;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
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
 * is skipped, not waited for, and a failure keeps the batches before it. A run stops after {@link #MAX_BATCHES}; what it
 * leaves, and the exports it skipped, the next run expires.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("Pruning account-scoped, workspace-agnostic export rows")
public class ExportRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(ExportRetentionSweeper.class);

    /** Batches in one run: 100 exports each, well inside {@code lockAtMostFor}. */
    static final int MAX_BATCHES = 50;

    private final AccountExportService accountExportService;
    private final PrivacyJobMetrics metrics;

    public ExportRetentionSweeper(AccountExportService accountExportService, PrivacyJobMetrics metrics) {
        this.accountExportService = accountExportService;
        this.metrics = metrics;
    }

    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "account-export-retention-sweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void sweep() {
        int expired = 0;
        try {
            int batch;
            int batches = 0;
            do {
                batch = accountExportService.expireRetention();
                expired += batch;
            } while (batch > 0 && ++batches < MAX_BATCHES);
            metrics.record(Job.EXPORT_RETENTION, batch > 0 ? Outcome.INCOMPLETE : Outcome.SUCCESS);
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
