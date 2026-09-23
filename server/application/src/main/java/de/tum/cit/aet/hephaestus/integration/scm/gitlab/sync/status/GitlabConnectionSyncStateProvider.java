package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.status;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.framework.CronSchedules;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.BackfillSummary;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncDetails;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncStateProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.RateLimitSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepoBackfillProgress;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResourceState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceInitializationService;
import de.tum.cit.aet.hephaestus.integration.scm.sync.status.ScmBackfillRollup;
import de.tum.cit.aet.hephaestus.integration.scm.sync.status.ScmResourceCountReader;
import de.tum.cit.aet.hephaestus.integration.scm.sync.status.ScmResourceCountReader.ScmResourceCounts;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** Read-only GitLab sync state built without vendor API calls. */
@Component
@ConditionalOnBean(GitLabWorkspaceInitializationService.class)
public class GitlabConnectionSyncStateProvider implements ConnectionSyncStateProvider {

    private final ConnectionService connectionService;
    private final ObjectProvider<GitLabRateLimitTracker> rateLimitTrackerProvider;
    private final SyncSchedulerProperties syncSchedulerProperties;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final RepositoryRepository repositoryRepository;
    private final ScmResourceCountReader countReader;

    public GitlabConnectionSyncStateProvider(
            ConnectionService connectionService,
            ObjectProvider<GitLabRateLimitTracker> rateLimitTrackerProvider,
            SyncSchedulerProperties syncSchedulerProperties,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            RepositoryRepository repositoryRepository,
            ScmResourceCountReader countReader) {
        this.connectionService = connectionService;
        this.rateLimitTrackerProvider = rateLimitTrackerProvider;
        this.syncSchedulerProperties = syncSchedulerProperties;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.repositoryRepository = repositoryRepository;
        this.countReader = countReader;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public ConnectionSyncDetails describe(IntegrationRef ref, long connectionId) {
        long workspaceId = ref.workspaceId();
        Optional<ConnectionConfig.GitLabConfig> config = connectionService.findActiveGitLabConfig(workspaceId);
        Boolean webhookRegistered = config.map(c -> c.gitlabWebhookId() != null).orElse(null);

        GitLabRateLimitTracker tracker = rateLimitTrackerProvider.getIfAvailable();
        RateLimitSnapshot rateLimit = tracker == null ? null : tracker.snapshot(workspaceId);

        // GitLab's scheduled backfill (GitLabHistoricalBackfillService#runBackfillCycle) writes the same
        // repository_to_monitor high-water-mark / checkpoint columns as GitHub, honors the same
        // hephaestus.sync.backfill.enabled flag, and its runner reports supportsBackfill()=true, so the
        // rollup is computed identically via the shared helper.
        BackfillSummary backfill = ScmBackfillRollup.summarize(
                syncSchedulerProperties.backfill().enabled(),
                repositoryToMonitorRepository.findByWorkspaceId(workspaceId).stream()
                        .map(GitlabConnectionSyncStateProvider::toBackfillProgress)
                        .toList());

        return new ConnectionSyncDetails(
                webhookRegistered,
                CronSchedules.nextRun(syncSchedulerProperties.cron()),
                CronSchedules.interval(syncSchedulerProperties.cron()),
                rateLimit,
                backfill,
                false);
    }

    @Override
    public List<SyncResourceState> resources(IntegrationRef ref, long connectionId) {
        long workspaceId = ref.workspaceId();
        List<RepositoryToMonitor> monitors = repositoryToMonitorRepository.findByWorkspaceId(workspaceId);
        if (monitors.isEmpty()) {
            return List.of();
        }

        Map<String, Repository> reposByNameWithOwner =
                repositoryRepository.findAllByWorkspaceMonitors(workspaceId).stream()
                        .collect(Collectors.toMap(Repository::getNameWithOwner, r -> r, (a, b) -> a));

        List<Long> repositoryIds =
                reposByNameWithOwner.values().stream().map(Repository::getId).toList();
        Map<Long, ScmResourceCounts> countsByRepositoryId = countReader.countsByRepositoryId(repositoryIds);

        return monitors.stream()
                .map(monitor -> toResourceState(monitor, reposByNameWithOwner, countsByRepositoryId))
                .toList();
    }

    private SyncResourceState toResourceState(
            RepositoryToMonitor monitor,
            Map<String, Repository> reposByNameWithOwner,
            Map<Long, ScmResourceCounts> countsByRepositoryId) {
        Repository repo = reposByNameWithOwner.get(monitor.getNameWithOwner());
        ScmResourceCounts counts =
                repo == null ? null : countsByRepositoryId.getOrDefault(repo.getId(), ScmResourceCounts.empty());
        Long itemCount = counts == null ? null : counts.headlineItemCount();

        // GitlabDataSyncScheduler.updateSyncTimestamp persists per-class watermarks on
        // repository_to_monitor (issues_synced_at / pull_requests_synced_at) on every completed phase,
        // keyed by the same monitor row iterated here.
        Instant issuesSyncedAt = monitor.getIssuesSyncedAt();
        Instant pullRequestsSyncedAt = monitor.getPullRequestsSyncedAt();
        Instant lastSyncedAt = latestNonNull(
                issuesSyncedAt,
                pullRequestsSyncedAt,
                monitor.getRepositorySyncedAt(),
                repo == null ? null : repo.getLastSyncAt());
        String state = lastSyncedAt != null ? SyncResourceState.STATE_SYNCED : SyncResourceState.STATE_PENDING;

        return new SyncResourceState(
                monitor.getId(),
                monitor.getNameWithOwner(),
                monitor.getNameWithOwner(),
                SyncResourceState.Type.REPOSITORY,
                state,
                lastSyncedAt,
                itemCount,
                // Per-class breakdown, each class carrying its own persisted watermark. Issues and pull
                // requests are reported per class rather than collapsed via latestNonNull above, so
                // "pull requests are fresh but issues stalled" stays visible instead of the newest sibling
                // masking the older one.
                counts == null ? List.of() : counts.toSyncResourceCounts(issuesSyncedAt, pullRequestsSyncedAt),
                null,
                monitor.getLastSyncError(),
                null,
                null);
    }

    /** Most recent of the non-null instants, or null if all are null. */
    private static @Nullable Instant latestNonNull(@Nullable Instant... candidates) {
        Instant latest = null;
        for (Instant candidate : candidates) {
            if (candidate != null && (latest == null || candidate.isAfter(latest))) {
                latest = candidate;
            }
        }
        return latest;
    }

    /** Projects a monitored-repository row into the vendor-neutral rollup input for {@link ScmBackfillRollup}. */
    private static RepoBackfillProgress toBackfillProgress(RepositoryToMonitor monitor) {
        return new RepoBackfillProgress(
                monitor.isBackfillInitialized(),
                monitor.isBackfillComplete(),
                monitor.getIssueBackfillHighWaterMark(),
                monitor.getPullRequestBackfillHighWaterMark(),
                monitor.getBackfillRemaining());
    }
}
