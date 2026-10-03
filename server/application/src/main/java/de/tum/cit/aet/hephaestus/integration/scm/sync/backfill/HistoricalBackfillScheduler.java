package de.tum.cit.aet.hephaestus.integration.scm.sync.backfill;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill.GitHubHistoricalBackfillService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.backfill.GitLabHistoricalBackfillService;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for historical backfill operations.
 *
 * <h2>Purpose</h2>
 * Runs scheduled backfill cycles for SCM providers that need historical data
 * predating the initial sync window. Each enabled provider's backfill service is
 * invoked once per cycle; rate-limit and cooldown handling lives inside the
 * per-vendor services, not here.
 *
 * <h2>Design Rationale</h2>
 * <p>Backfill is handled exclusively via scheduled batches, not event-driven triggers.
 * This is intentional:
 * <ul>
 *   <li><b>Simplicity:</b> One mechanism handles all backfill — no event/scheduler coordination</li>
 *   <li><b>API respect:</b> Doesn't hammer the provider right after sync depletes rate limit</li>
 *   <li><b>Historical data isn't urgent:</b> Data sitting for months/years can wait minutes</li>
 *   <li><b>Built-in guards:</b> Each backfill service checks rate limits, cooldowns, and
 *       last-synced timestamps to skip repos that haven't completed initial sync yet</li>
 * </ul>
 *
 * <p>Configuration lives on {@link SyncSchedulerProperties.BackfillProperties}.
 *
 * @see GitHubHistoricalBackfillService
 * @see GitLabHistoricalBackfillService
 * @see SyncSchedulerProperties.BackfillProperties
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hephaestus.sync.backfill.enabled", havingValue = "true")
@WorkspaceAgnostic("Workspace iteration is owned by the per-vendor backfill services "
        + "(GitHubHistoricalBackfillService.runBackfillCycle and GitLabHistoricalBackfillService.runBackfillCycle); "
        + "the scheduler is a thin trigger.")
public class HistoricalBackfillScheduler {

    private final GitHubHistoricalBackfillService backfillService;
    private final SyncSchedulerProperties syncSchedulerProperties;
    private final ObjectProvider<GitLabHistoricalBackfillService> gitLabBackfillServiceProvider;

    @PostConstruct
    void logInitialization() {
        log.info(
                "Historical backfill scheduler initialized: batchSize={}, rateLimitThreshold={}, intervalSeconds={}",
                syncSchedulerProperties.backfill().batchSize(),
                syncSchedulerProperties.backfill().rateLimitThreshold(),
                syncSchedulerProperties.backfill().intervalSeconds());
    }

    /**
     * Scheduled task that processes backfill operations.
     *
     * <p>This method is called automatically by Spring's task scheduler at a fixed rate.
     * Each invocation processes one batch of historical data per repository that has
     * completed initial sync but still needs backfill, respecting rate limits and cooldowns.
     *
     * <p>When all repositories have completed backfill, cycles run silently at TRACE level
     * to avoid log noise while remaining ready for new repositories.
     */
    @Scheduled(fixedRateString = "${hephaestus.sync.backfill.interval-seconds:60}", timeUnit = TimeUnit.SECONDS)
    public void runBackfillCycle() {
        log.trace("Starting backfill cycle");
        try {
            GitHubHistoricalBackfillService.BackfillCycleResult result = backfillService.runBackfillCycle();
            if (result.repositoriesProcessed() > 0) {
                log.info("Backfill cycle complete: repositoriesProcessed={}", result.repositoriesProcessed());
            } else if (result.pendingRepositories() > 0) {
                // There are repos that need backfill but were skipped (rate limit, cooldown, etc.)
                log.debug(
                        "Backfill cycle complete: no work performed, pendingRepos={}, reason={}",
                        result.pendingRepositories(),
                        result.skipReason());
            }
            // If pendingRepositories == 0, all backfill is complete - stay silent (TRACE only)
        } catch (Exception e) {
            log.error("Backfill cycle failed", e);
        }

        // GitLab backfill (runs alongside GitHub backfill)
        try {
            GitLabHistoricalBackfillService gitLabBackfill = gitLabBackfillServiceProvider.getIfAvailable();
            if (gitLabBackfill != null) {
                int gitLabProcessed = gitLabBackfill.runBackfillCycle();
                if (gitLabProcessed > 0) {
                    log.info("GitLab backfill cycle complete: repositoriesProcessed={}", gitLabProcessed);
                }
            }
        } catch (Exception e) {
            log.error("GitLab backfill cycle failed", e);
        }
    }
}
