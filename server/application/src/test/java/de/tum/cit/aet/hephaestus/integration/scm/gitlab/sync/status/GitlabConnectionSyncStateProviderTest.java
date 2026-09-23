package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.BackfillProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.BackfillSummary;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncDetails;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.RateLimitSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResourceCount;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResourceState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.sync.status.ScmResourceCountReader;
import de.tum.cit.aet.hephaestus.integration.scm.sync.status.ScmResourceCountReader.ScmResourceCounts;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class GitlabConnectionSyncStateProviderTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 7L;
    private static final long CONNECTION_ID = 70L;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private ObjectProvider<GitLabRateLimitTracker> rateLimitTrackerProvider;

    @Mock
    private GitLabRateLimitTracker rateLimitTracker;

    @Mock
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private ScmResourceCountReader countReader;

    private GitlabConnectionSyncStateProvider provider;
    private IntegrationRef ref;

    @BeforeEach
    void setUp() {
        SyncSchedulerProperties syncProps = new SyncSchedulerProperties(
                true,
                7,
                "0 0 3 * * *",
                15,
                new SyncSchedulerProperties.BackfillProperties(false, 50, 100, 60),
                new SyncSchedulerProperties.FilterProperties(Set.of(), Set.of(), Set.of()),
                new SyncSchedulerProperties.DiscussionsProperties(false),
                new SyncSchedulerProperties.ProjectsProperties(false));
        provider = new GitlabConnectionSyncStateProvider(
                connectionService,
                rateLimitTrackerProvider,
                syncProps,
                repositoryToMonitorRepository,
                repositoryRepository,
                countReader);
        ref = new IntegrationRef(IntegrationKind.GITLAB, WORKSPACE_ID, "gitlab.com:1");
        lenient().when(rateLimitTrackerProvider.getIfAvailable()).thenReturn(rateLimitTracker);
    }

    @Nested
    class Describe {

        @Test
        void shouldReportWebhookRegisteredWhenIdPresent() {
            when(connectionService.findActiveGitLabConfig(WORKSPACE_ID)).thenReturn(Optional.of(gitLabConfig(99L)));

            ConnectionSyncDetails details = provider.describe(ref, CONNECTION_ID);

            assertThat(details.webhookRegistered()).isTrue();
        }

        @Test
        void shouldReportWebhookNotRegisteredWhenIdAbsent() {
            when(connectionService.findActiveGitLabConfig(WORKSPACE_ID)).thenReturn(Optional.of(gitLabConfig(null)));

            ConnectionSyncDetails details = provider.describe(ref, CONNECTION_ID);

            assertThat(details.webhookRegistered()).isFalse();
        }

        @Test
        void shouldReportUnknownWebhookWhenNoActiveConfig() {
            when(connectionService.findActiveGitLabConfig(WORKSPACE_ID)).thenReturn(Optional.empty());

            ConnectionSyncDetails details = provider.describe(ref, CONNECTION_ID);

            assertThat(details.webhookRegistered()).isNull();
        }

        @Test
        void shouldReportRateLimitSnapshotFromTracker() {
            when(connectionService.findActiveGitLabConfig(WORKSPACE_ID)).thenReturn(Optional.empty());
            RateLimitSnapshot snapshot =
                    new RateLimitSnapshot(100, 42, Instant.now().plusSeconds(30), Instant.now(), null);
            when(rateLimitTracker.snapshot(WORKSPACE_ID)).thenReturn(snapshot);

            ConnectionSyncDetails details = provider.describe(ref, CONNECTION_ID);

            assertThat(details.rateLimit()).isEqualTo(snapshot);
        }

        @Test
        void shouldReportNullRateLimitWhenTrackerUnavailable() {
            when(connectionService.findActiveGitLabConfig(WORKSPACE_ID)).thenReturn(Optional.empty());
            when(rateLimitTrackerProvider.getIfAvailable()).thenReturn(null);

            ConnectionSyncDetails details = provider.describe(ref, CONNECTION_ID);

            assertThat(details.rateLimit()).isNull();
        }

        @Test
        void shouldComputeNextScheduledSyncAtFromCron() {
            when(connectionService.findActiveGitLabConfig(WORKSPACE_ID)).thenReturn(Optional.empty());
            Instant before = Instant.now();

            ConnectionSyncDetails details = provider.describe(ref, CONNECTION_ID);

            // The configured cron fires daily at 03:00: assert the actual next occurrence, not merely
            // "in the future" (which any future instant, e.g. now+1s, would satisfy).
            Instant next = details.nextScheduledSyncAt();
            assertThat(next).isNotNull();
            ZonedDateTime fire = next.atZone(ZoneId.systemDefault());
            assertThat(fire.getHour()).isEqualTo(3);
            assertThat(fire.getMinute()).isZero();
            assertThat(fire.getSecond()).isZero();
            // 25h, not 24h: on the DST fall-back day the gap to the next 03:00 fire stretches to 25h.
            assertThat(next).isAfter(before).isBeforeOrEqualTo(before.plus(Duration.ofHours(25)));
        }

        @Test
        void shouldYieldNullNextScheduledSyncAtForAnInvalidCron() {
            when(connectionService.findActiveGitLabConfig(WORKSPACE_ID)).thenReturn(Optional.empty());
            GitlabConnectionSyncStateProvider brokenCron = new GitlabConnectionSyncStateProvider(
                    connectionService,
                    rateLimitTrackerProvider,
                    new SyncSchedulerProperties(
                            true,
                            7,
                            "not a cron",
                            15,
                            new SyncSchedulerProperties.BackfillProperties(false, 50, 100, 60),
                            new SyncSchedulerProperties.FilterProperties(Set.of(), Set.of(), Set.of()),
                            new SyncSchedulerProperties.DiscussionsProperties(false),
                            new SyncSchedulerProperties.ProjectsProperties(false)),
                    repositoryToMonitorRepository,
                    repositoryRepository,
                    countReader);

            assertThat(brokenCron.describe(ref, CONNECTION_ID).nextScheduledSyncAt())
                    .isNull();
        }

        @Test
        void shouldComputeSyncIntervalFromCron() {
            when(connectionService.findActiveGitLabConfig(WORKSPACE_ID)).thenReturn(Optional.empty());

            ConnectionSyncDetails details = provider.describe(ref, CONNECTION_ID);

            // Without the cadence a "last synced 4h ago" reading is unjudgeable — callers cannot tell
            // stale from on-schedule. The configured cron fires daily, so the cadence is 24h.
            assertThat(details.syncInterval()).isEqualTo(Duration.ofHours(24));
        }

        @Test
        void shouldNeverReportVendorHealthDegraded() {
            when(connectionService.findActiveGitLabConfig(WORKSPACE_ID)).thenReturn(Optional.empty());

            ConnectionSyncDetails details = provider.describe(ref, CONNECTION_ID);

            // GitLab has no independent vendor-health signal (unlike GitHub App suspension), so this
            // stays false — the connection is only DEGRADED via a failed job or errored resource.
            assertThat(details.vendorHealthDegraded()).isFalse();
        }

        @Test
        void shouldReportBackfillDisabledWhenScheduledBackfillOff() {
            // The default scheduler props (setUp) leave backfill disabled — parity with GitHub, which
            // reports "DISABLED" for the scheduled-backfill diagnostic when the flag is off.
            when(connectionService.findActiveGitLabConfig(WORKSPACE_ID)).thenReturn(Optional.empty());

            ConnectionSyncDetails details = provider.describe(ref, CONNECTION_ID);

            assertThat(details.backfill()).isEqualTo(new BackfillSummary("DISABLED", null));
        }

        @Test
        void shouldReportBackfillNotStartedWhenEnabledButNoMonitoredRepos() {
            when(connectionService.findActiveGitLabConfig(WORKSPACE_ID)).thenReturn(Optional.empty());
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of());

            ConnectionSyncDetails details = backfillEnabledProvider().describe(ref, CONNECTION_ID);

            assertNotNull(details.backfill());
            assertThat(details.backfill().state()).isEqualTo("NOT_STARTED");
            assertThat(details.backfill().percent()).isNull();
        }

        @Test
        void shouldReportBackfillCompleteWithFullPercentWhenAllReposDone() {
            when(connectionService.findActiveGitLabConfig(WORKSPACE_ID)).thenReturn(Optional.empty());
            RepositoryToMonitor rtm = monitor(1L, "group/done-repo");
            rtm.setIssueBackfillHighWaterMark(100);
            rtm.setIssueBackfillCheckpoint(0);
            rtm.setPullRequestBackfillHighWaterMark(0);
            rtm.setPullRequestBackfillCheckpoint(0);
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(rtm));

            ConnectionSyncDetails details = backfillEnabledProvider().describe(ref, CONNECTION_ID);

            assertNotNull(details.backfill());
            assertThat(details.backfill().state()).isEqualTo("COMPLETE");
            assertThat(details.backfill().percent()).isEqualTo(100);
        }

        @Test
        void shouldReportBackfillInProgressWithCoarsePercentWhenPartiallyDone() {
            when(connectionService.findActiveGitLabConfig(WORKSPACE_ID)).thenReturn(Optional.empty());
            RepositoryToMonitor rtm = monitor(2L, "group/partial-repo");
            // High-water-mark 100, checkpoint (lowest IID still to reach) at 25 → 75 of 100 done.
            rtm.setIssueBackfillHighWaterMark(100);
            rtm.setIssueBackfillCheckpoint(25);
            rtm.setPullRequestBackfillHighWaterMark(0);
            rtm.setPullRequestBackfillCheckpoint(0);
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(rtm));

            ConnectionSyncDetails details = backfillEnabledProvider().describe(ref, CONNECTION_ID);

            assertNotNull(details.backfill());
            assertThat(details.backfill().state()).isEqualTo("IN_PROGRESS");
            assertThat(details.backfill().percent()).isEqualTo(75);
        }

        private GitlabConnectionSyncStateProvider backfillEnabledProvider() {
            return new GitlabConnectionSyncStateProvider(
                    connectionService,
                    rateLimitTrackerProvider,
                    new SyncSchedulerProperties(
                            true, 7, "0 0 3 * * *", 15, new BackfillProperties(true, 50, 100, 60), null, null, null),
                    repositoryToMonitorRepository,
                    repositoryRepository,
                    countReader);
        }

        private RepositoryToMonitor monitor(long id, String nameWithOwner) {
            RepositoryToMonitor m = new RepositoryToMonitor();
            ReflectionTestUtils.setField(m, "id", id);
            m.setNameWithOwner(nameWithOwner);
            return m;
        }

        private ConnectionConfig.GitLabConfig gitLabConfig(@Nullable Long webhookId) {
            return new ConnectionConfig.GitLabConfig(
                    "https://gitlab.com", 1L, webhookId, ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT, Set.of());
        }
    }

    @Nested
    class Resources {

        @Test
        void shouldReturnEmptyWhenNoMonitors() {
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of());

            List<SyncResourceState> resources = provider.resources(ref, CONNECTION_ID);

            assertThat(resources).isEmpty();
        }

        @Test
        void shouldMapSyncedRepositoryWithItemCount() {
            RepositoryToMonitor monitor = monitor(1L, "group/synced-repo");
            monitor.setLastSyncError("Issue sync: ABORTED_ERROR");
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(monitor));

            Repository repo = new Repository();
            ReflectionTestUtils.setField(repo, "id", 500L);
            repo.setNameWithOwner("group/synced-repo");
            Instant syncedAt = Instant.now().minusSeconds(60);
            repo.setLastSyncAt(syncedAt);
            when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenReturn(List.of(repo));

            // Headline itemCount is issues + merge requests: 9 + 3 = 12.
            when(countReader.countsByRepositoryId(List.of(500L)))
                    .thenReturn(Map.of(500L, new ScmResourceCounts(9, 3, 0, 0, 0, 0)));

            List<SyncResourceState> resources = provider.resources(ref, CONNECTION_ID);

            assertThat(resources).hasSize(1);
            SyncResourceState resource = resources.get(0);
            assertThat(resource.id()).isEqualTo(1L);
            assertThat(resource.externalId()).isEqualTo("group/synced-repo");
            assertThat(resource.type()).isEqualTo(SyncResourceState.Type.REPOSITORY);
            assertThat(resource.state()).isEqualTo("SYNCED");
            assertThat(resource.lastSyncedAt()).isEqualTo(syncedAt);
            assertThat(resource.itemCount()).isEqualTo(12L);
            assertThat(resource.lastError()).isEqualTo("Issue sync: ABORTED_ERROR");
        }

        @Test
        void shouldMapUnsyncedRepositoryAsPendingWithNullItemCount() {
            RepositoryToMonitor monitor = monitor(2L, "group/new-repo");
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(monitor));
            when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenReturn(List.of());

            List<SyncResourceState> resources = provider.resources(ref, CONNECTION_ID);

            assertThat(resources).hasSize(1);
            SyncResourceState resource = resources.get(0);
            assertThat(resource.state()).isEqualTo("PENDING");
            assertThat(resource.lastSyncedAt()).isNull();
            assertThat(resource.itemCount()).isNull();
        }

        @Test
        void shouldDefaultItemCountToZeroWhenRepositoryHasNoIssuesOrMrs() {
            RepositoryToMonitor monitor = monitor(3L, "group/empty-repo");
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(monitor));

            Repository repo = new Repository();
            ReflectionTestUtils.setField(repo, "id", 501L);
            repo.setNameWithOwner("group/empty-repo");
            repo.setLastSyncAt(Instant.now());
            when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenReturn(List.of(repo));
            // No rows at all for the repository: it is absent from the lookup entirely.
            when(countReader.countsByRepositoryId(List.of(501L))).thenReturn(Map.of());

            List<SyncResourceState> resources = provider.resources(ref, CONNECTION_ID);

            assertThat(resources.get(0).itemCount()).isEqualTo(0L);
        }

        @Test
        void shouldReportAllSixEntityClassesWithCountsRollingUpToItemCount() {
            RepositoryToMonitor monitor = monitor(4L, "group/full-repo");
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(monitor));

            Repository repo = new Repository();
            ReflectionTestUtils.setField(repo, "id", 502L);
            repo.setNameWithOwner("group/full-repo");
            repo.setLastSyncAt(Instant.now());
            when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenReturn(List.of(repo));
            when(countReader.countsByRepositoryId(List.of(502L)))
                    .thenReturn(Map.of(502L, new ScmResourceCounts(9, 3, 13, 14, 15, 16)));

            List<SyncResourceState> resources = provider.resources(ref, CONNECTION_ID);

            SyncResourceState resource = resources.get(0);
            assertThat(resource.counts())
                    .extracting(SyncResourceCount::key, SyncResourceCount::count)
                    .containsExactly(
                            tuple("issues", 9L),
                            tuple("pullRequests", 3L),
                            tuple("issueComments", 13L),
                            tuple("reviews", 14L),
                            tuple("reviewComments", 15L),
                            tuple("commits", 16L));
            // Headline is issues + merge requests only; the notes/commit counts must not leak in.
            assertThat(resource.itemCount()).isEqualTo(12L);
        }

        @Test
        void shouldReportThePerClassWatermarksGitlabPersistsAndNoOthers() {
            RepositoryToMonitor monitor = monitor(5L, "group/honest-repo");
            // GitlabDataSyncScheduler.updateSyncTimestamp writes these two columns on every completed
            // phase, keyed by this monitor row — so the provider must read them rather than discard them.
            monitor.setIssuesSyncedAt(Instant.parse("2026-07-10T03:00:00Z"));
            monitor.setPullRequestsSyncedAt(Instant.parse("2026-07-14T03:00:00Z"));
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(monitor));

            Repository repo = new Repository();
            ReflectionTestUtils.setField(repo, "id", 503L);
            repo.setNameWithOwner("group/honest-repo");
            Instant repoSyncedAt = Instant.parse("2026-07-15T03:00:00Z");
            repo.setLastSyncAt(repoSyncedAt);
            when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenReturn(List.of(repo));
            when(countReader.countsByRepositoryId(List.of(503L)))
                    .thenReturn(Map.of(503L, new ScmResourceCounts(9, 3, 13, 14, 15, 16)));

            List<SyncResourceState> resources = provider.resources(ref, CONNECTION_ID);
            SyncResourceState resource = resources.get(0);

            // Row level reports the most recent watermark of any kind — here the repository-wide one.
            assertThat(resource.lastSyncedAt()).isEqualTo(repoSyncedAt);

            // Issues and pull requests each carry their OWN persisted watermark, reported per class
            // rather than collapsed into lastSyncedAt above — that collapse is what would hide
            // "pull requests are fresh but issues stalled four days ago" behind the newest sibling.
            assertThat(resource.counts()).hasSize(6);
            assertThat(resource.counts().get(0).lastSyncedAt()).isEqualTo(Instant.parse("2026-07-10T03:00:00Z"));
            assertThat(resource.counts().get(1).lastSyncedAt()).isEqualTo(Instant.parse("2026-07-14T03:00:00Z"));

            // The nested classes have no independent sync phase and therefore no watermark column of
            // their own. They stay null — "not tracked" — rather than borrowing a sibling's timestamp
            // and asserting a per-class freshness nobody measured.
            assertThat(resource.counts().subList(2, 6))
                    .allSatisfy(count -> assertThat(count.lastSyncedAt()).isNull());
        }

        private RepositoryToMonitor monitor(long id, String nameWithOwner) {
            RepositoryToMonitor monitor = new RepositoryToMonitor();
            ReflectionTestUtils.setField(monitor, "id", id);
            monitor.setNameWithOwner(nameWithOwner);
            return monitor;
        }
    }
}
