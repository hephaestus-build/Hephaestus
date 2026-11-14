package de.tum.cit.aet.hephaestus.integration.slack.retention;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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

    /** Tool results erased in one transaction; a run erases at most {@link #MAX_COPIES_PER_RUN} per workspace. */
    static final int COPY_BATCH = 500;

    static final int MAX_COPIES_PER_RUN = 20_000;

    private final SlackMessageRepository slackMessageRepository;
    private final ConnectionService connectionService;
    private final SlackRetentionPurger retentionPurger;
    private final ConversationEntryService conversationEntries;

    public SlackRetentionSweeper(
            SlackMessageRepository slackMessageRepository,
            ConnectionService connectionService,
            SlackRetentionPurger retentionPurger,
            ConversationEntryService conversationEntries) {
        this.slackMessageRepository = slackMessageRepository;
        this.connectionService = connectionService;
        this.retentionPurger = retentionPurger;
        this.conversationEntries = conversationEntries;
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

    /**
     * Once a week, erases the conversation tool results that could have copied a channel message, up to two weeks before
     * they reach the workspace's window, so a missed run still keeps it. Nothing records which source a result copied, so each one goes as though it copied
     * the oldest message it could have read: a copy lives at most one window, and the words it copied at most two.
     * Weekly, because an erased entry also erases every summary over it, and a nightly erasure would cost each long
     * conversation a full compaction every night.
     */
    @Scheduled(cron = "0 40 3 * * SUN")
    @SchedulerLock(name = "slack-copy-retention-sweep", lockAtMostFor = "PT3H", lockAtLeastFor = "PT30S")
    public void sweepCopies() {
        sweepCopiesNow();
    }

    /** @return how many tool results it erased across every workspace */
    public long sweepCopiesNow() {
        // A conversation's tool results can hold channel messages after the messages aged out, while the connection
        // lasts; an uninstall erased them with the rest of the Slack content.
        Set<Long> workspaceIds = new TreeSet<>(slackMessageRepository.findDistinctWorkspaceIds());
        workspaceIds.addAll(connectionService.findWorkspaceIdsWithConnection(
                IntegrationKind.SLACK, IntegrationState.ACTIVE, IntegrationState.SUSPENDED));
        Instant now = Instant.now();
        long erased = 0;
        for (Long workspaceId : workspaceIds) {
            Instant cutoff = now.minus(Duration.ofDays(Math.max(1, retentionWindowDays(workspaceId) - 14)));
            try {
                int done;
                int inWorkspace = 0;
                do {
                    done = conversationEntries.redactCopyingToolResultsWrittenBefore(workspaceId, cutoff, COPY_BATCH);
                    inWorkspace += done;
                } while (done == COPY_BATCH && inWorkspace < MAX_COPIES_PER_RUN);
                if (inWorkspace >= MAX_COPIES_PER_RUN) {
                    log.warn(
                            "slack.retention: workspaceId={} has more expired tool results than one run erases",
                            workspaceId);
                }
                erased += inWorkspace;
            } catch (RuntimeException e) {
                log.warn("slack.retention: copy sweep failed for workspaceId={}: {}", workspaceId, e.toString());
            }
        }
        return erased;
    }

    private int retentionWindowDays(long workspaceId) {
        int configured = connectionService
                .findSlackConfig(workspaceId)
                .map(ConnectionConfig.SlackConfig::retentionDaysOrDefault)
                .orElse(ConnectionConfig.SlackConfig.DEFAULT_RETENTION_DAYS);
        return Math.min(configured, MAX_RETENTION_DAYS);
    }
}
