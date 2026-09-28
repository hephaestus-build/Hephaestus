package de.tum.cit.aet.hephaestus.integration.slack.retention;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded-retention sweep for ingested Slack messages.
 *
 * <p>Enumerates every workspace that currently holds at least one {@code slack_message} row, resolves
 * that workspace's retention window (its {@link ConnectionConfig.SlackConfig#retentionDaysOrDefault()},
 * hard-capped at {@value #MAX_RETENTION_DAYS} days no matter how it is configured), and delegates the
 * actual delete to {@link SlackRetentionPurger} — a separate bean whose {@code REQUIRES_NEW}
 * boundary keeps each workspace's prune isolated. A failure in one workspace is logged and the sweep
 * continues.
 *
 * <p>This bean owns only scheduling, cross-pod locking, and window arithmetic. It is
 * {@link WorkspaceAgnostic} because {@link SlackMessageRepository#findDistinctWorkspaceIds()} is an
 * unscoped native query — the tenancy inspector would otherwise reject it — and because the sweep is
 * inherently cross-workspace. Scheduling is gated to the server role, and {@link SchedulerLock}
 * stops concurrent pods from both running it.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("Pruning Slack content across all workspaces on a bounded-retention schedule")
public class SlackRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(SlackRetentionSweeper.class);

    /** Hard privacy ceiling: Slack content is never retained beyond this many days, whatever the config says. */
    public static final int MAX_RETENTION_DAYS = 180;

    private final SlackMessageRepository slackMessageRepository;
    private final ConnectionService connectionService;
    private final SlackRetentionPurger retentionPurger;

    public SlackRetentionSweeper(
            SlackMessageRepository slackMessageRepository,
            ConnectionService connectionService,
            SlackRetentionPurger retentionPurger) {
        this.slackMessageRepository = slackMessageRepository;
        this.connectionService = connectionService;
        this.retentionPurger = retentionPurger;
    }

    @Scheduled(cron = "0 30 3 * * *")
    @SchedulerLock(name = "slack-retention-sweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void sweep() {
        sweepNow();
    }

    /**
     * Run the sweep immediately across every workspace with ingested Slack messages. Exposed (rather
     * than invoked only via the cron) so callers and integration tests can drive it deterministically.
     *
     * @return the total number of {@code slack_message} rows deleted across all workspaces
     */
    public long sweepNow() {
        List<Long> workspaceIds = slackMessageRepository.findDistinctWorkspaceIds();
        long totalDeleted = 0;
        Instant now = Instant.now();
        for (Long workspaceId : workspaceIds) {
            int window = retentionWindowDays(workspaceId);
            Instant cutoff = now.minus(Duration.ofDays(window));
            try {
                totalDeleted += retentionPurger.purgeWorkspaceBefore(workspaceId, cutoff);
            } catch (RuntimeException e) {
                // Isolate a poisoned workspace — log and keep pruning the rest.
                log.warn("slack.retention: sweep failed for workspaceId={}: {}", workspaceId, e.toString());
            }
        }
        if (totalDeleted > 0) {
            log.info("slack.retention: pruned {} message(s) across {} workspace(s)", totalDeleted, workspaceIds.size());
        }
        return totalDeleted;
    }

    private int retentionWindowDays(long workspaceId) {
        int configured = connectionService
                .findSlackConfig(workspaceId)
                .map(ConnectionConfig.SlackConfig::retentionDaysOrDefault)
                .orElse(ConnectionConfig.SlackConfig.DEFAULT_RETENTION_DAYS);
        return Math.min(configured, MAX_RETENTION_DAYS);
    }
}
