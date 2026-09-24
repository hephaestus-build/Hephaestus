package de.tum.cit.aet.hephaestus.integration.scm.github.sync.status;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.framework.CronSchedules;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncDetails;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncStateProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepoBackfillProgress;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResourceState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ScopedRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill.GitHubHistoricalBackfillService.BackfillProgress;
import de.tum.cit.aet.hephaestus.integration.scm.github.workspace.GitHubInstallationSuspensionTracker;
import de.tum.cit.aet.hephaestus.integration.scm.sync.status.ScmBackfillRollup;
import de.tum.cit.aet.hephaestus.integration.scm.sync.status.ScmResourceCountReader;
import de.tum.cit.aet.hephaestus.integration.scm.sync.status.ScmResourceCountReader.ScmResourceCounts;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * GitHub {@link ConnectionSyncStateProvider}: connection-level sync snapshot and per-repository
 * resource rows for the sync-observability read model.
 *
 * <p>Both {@link #describe} and {@link #resources} are O(DB + in-memory) only — no live GitHub API
 * call — per the SPI contract: the overview page renders every connected integration on one load.
 */
@Component
public class GithubConnectionSyncStateProvider implements ConnectionSyncStateProvider {

    private final ConnectionRepository connectionRepository;
    private final ScopedRateLimitTracker rateLimitTracker;
    private final GitHubInstallationSuspensionTracker suspensionTracker;
    private final SyncSchedulerProperties syncSchedulerProperties;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final RepositoryRepository repositoryRepository;
    private final ScmResourceCountReader countReader;

    public GithubConnectionSyncStateProvider(
            ConnectionRepository connectionRepository,
            ScopedRateLimitTracker rateLimitTracker,
            GitHubInstallationSuspensionTracker suspensionTracker,
            SyncSchedulerProperties syncSchedulerProperties,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            RepositoryRepository repositoryRepository,
            ScmResourceCountReader countReader) {
        this.connectionRepository = connectionRepository;
        this.rateLimitTracker = rateLimitTracker;
        this.suspensionTracker = suspensionTracker;
        this.syncSchedulerProperties = syncSchedulerProperties;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.repositoryRepository = repositoryRepository;
        this.countReader = countReader;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public ConnectionSyncDetails describe(IntegrationRef ref, long connectionId) {
        long workspaceId = ref.workspaceId();
        Connection connection = connectionRepository.findById(connectionId).orElse(null);

        // App-installation workspaces have a vendor-managed webhook (registered at install time), so we
        // report TRUE. PAT connections have no webhook signal we track, so webhookRegistered stays null
        // (unknown) rather than a claimed FALSE.
        Boolean webhookRegistered = null;
        Long installationId = null;
        if (connection != null && connection.getConfig() instanceof ConnectionConfig.GitHubAppConfig appConfig) {
            webhookRegistered = Boolean.TRUE;
            installationId = appConfig.installationId();
        }

        boolean vendorHealthDegraded =
                installationId != null && suspensionTracker.isInstallationMarkedSuspended(installationId);

        return new ConnectionSyncDetails(
                webhookRegistered,
                CronSchedules.nextRun(syncSchedulerProperties.cron()),
                CronSchedules.interval(syncSchedulerProperties.cron()),
                rateLimitTracker.snapshot(workspaceId),
                ScmBackfillRollup.summarize(
                        syncSchedulerProperties.backfill().enabled(),
                        repositoryToMonitorRepository.findByWorkspaceId(workspaceId).stream()
                                .map(GithubConnectionSyncStateProvider::toBackfillProgress)
                                .toList()),
                vendorHealthDegraded);
    }

    @Override
    public List<SyncResourceState> resources(IntegrationRef ref, long connectionId) {
        long workspaceId = ref.workspaceId();
        List<RepositoryToMonitor> monitors = repositoryToMonitorRepository.findByWorkspaceId(workspaceId);
        if (monitors.isEmpty()) {
            return List.of();
        }

        // Batch-resolve nameWithOwner -> local Repository id in one query, then one fixed set of
        // grouped per-entity-class count queries — avoids an N+1 per monitored repository.
        Map<String, Long> repositoryIdByName = repositoryRepository.findAllByWorkspaceMonitors(workspaceId).stream()
                .collect(Collectors.toMap(Repository::getNameWithOwner, Repository::getId, (a, b) -> a));

        Map<Long, ScmResourceCounts> countsByRepositoryId =
                countReader.countsByRepositoryId(repositoryIdByName.values());

        return monitors.stream()
                .map(monitor -> toResourceState(monitor, repositoryIdByName, countsByRepositoryId))
                .toList();
    }

    private SyncResourceState toResourceState(
            RepositoryToMonitor monitor,
            Map<String, Long> repositoryIdByName,
            Map<Long, ScmResourceCounts> countsByRepositoryId) {
        Long repositoryId = repositoryIdByName.get(monitor.getNameWithOwner());
        // A monitor with no local Repository row has never synced anything — distinct from one that
        // synced zero items, so the headline stays null (unknown) rather than claiming 0.
        ScmResourceCounts counts = repositoryId == null ? null : countsByRepositoryId.get(repositoryId);
        Long itemCount = counts == null ? null : counts.headlineItemCount();

        Instant lastSyncedAt = latestNonNull(
                monitor.getIssuesSyncedAt(), monitor.getPullRequestsSyncedAt(), monitor.getRepositorySyncedAt());
        String state = monitor.getIssuesSyncedAt() == null || monitor.getPullRequestsSyncedAt() == null
                ? SyncResourceState.STATE_PENDING
                : SyncResourceState.STATE_SYNCED;

        int backfillTotal =
                (monitor.getIssueBackfillHighWaterMark() != null ? monitor.getIssueBackfillHighWaterMark() : 0)
                        + (monitor.getPullRequestBackfillHighWaterMark() != null
                                ? monitor.getPullRequestBackfillHighWaterMark()
                                : 0);
        Integer backfillPercent = BackfillProgress.percentComplete(
                monitor.isBackfillInitialized(), monitor.getBackfillRemaining(), backfillTotal);

        return new SyncResourceState(
                monitor.getId(),
                monitor.getNameWithOwner(),
                monitor.getNameWithOwner(),
                SyncResourceState.Type.REPOSITORY,
                state,
                lastSyncedAt,
                itemCount,
                // Per-class breakdown (issues, pull requests), each carrying its own watermark, rather than
                // collapsed into lastSyncedAt above — collapsing would hide "pull requests are fresh but
                // issues stopped four days ago" since the newest sibling wins.
                counts == null
                        ? List.of()
                        : counts.toSyncResourceCounts(monitor.getIssuesSyncedAt(), monitor.getPullRequestsSyncedAt()),
                // upstreamCount: would require a live vendor call — the SPI forbids that in resources().
                null,
                monitor.getSyncErrorSummary(),
                // backfillCompletedThrough: GitHub's backfill horizon is issue/PR-NUMBER based
                // (highWaterMark/checkpoint), not date-based — no per-item timestamp to report here
                // without an extra vendor call.
                null,
                backfillPercent);
    }

    @Nullable
    private static Instant latestNonNull(Instant... candidates) {
        Instant latest = null;
        for (Instant candidate : candidates) {
            if (candidate != null && (latest == null || candidate.isAfter(latest))) {
                latest = candidate;
            }
        }
        return latest;
    }

    /** Projects a monitored-repository row into {@link ScmBackfillRollup}'s vendor-neutral input. */
    private static RepoBackfillProgress toBackfillProgress(RepositoryToMonitor monitor) {
        return new RepoBackfillProgress(
                monitor.isBackfillInitialized(),
                monitor.isBackfillComplete(),
                monitor.getIssueBackfillHighWaterMark(),
                monitor.getPullRequestBackfillHighWaterMark(),
                monitor.getBackfillRemaining());
    }
}
