package de.tum.cit.aet.hephaestus.integration.scm.github.sync.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.BackfillProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.FilterProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.BackfillSummary;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncDetails;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.RateLimitSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResourceCount;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResourceState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ScopedRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.github.workspace.GitHubInstallationSuspensionTracker;
import de.tum.cit.aet.hephaestus.integration.scm.sync.status.ScmResourceCountReader;
import de.tum.cit.aet.hephaestus.integration.scm.sync.status.ScmResourceCountReader.ScmResourceCounts;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

class GithubConnectionSyncStateProviderTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long CONNECTION_ID = 10L;
    private static final long INSTALLATION_ID = 100L;

    @Mock
    private ConnectionRepository connectionRepository;

    @Mock
    private ScopedRateLimitTracker rateLimitTracker;

    @Mock
    private GitHubInstallationSuspensionTracker suspensionTracker;

    @Mock
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private ScmResourceCountReader countReader;

    private Workspace workspace;
    private IntegrationRef ref;

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        ref = new IntegrationRef(IntegrationKind.GITHUB, WORKSPACE_ID, String.valueOf(INSTALLATION_ID));
    }

    private GithubConnectionSyncStateProvider provider(SyncSchedulerProperties properties) {
        return new GithubConnectionSyncStateProvider(
                connectionRepository,
                rateLimitTracker,
                suspensionTracker,
                properties,
                repositoryToMonitorRepository,
                repositoryRepository,
                countReader);
    }

    private static SyncSchedulerProperties schedulerProperties(boolean backfillEnabled) {
        return new SyncSchedulerProperties(
                true,
                7,
                "0 0 3 * * *",
                15,
                new BackfillProperties(backfillEnabled, 50, 100, 60),
                new FilterProperties(Set.of(), Set.of(), Set.of()),
                null,
                null);
    }

    private Connection githubAppConnection() {
        Connection connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                String.valueOf(INSTALLATION_ID),
                new ConnectionConfig.GitHubAppConfig(INSTALLATION_ID, "acme", null, Set.of()));
        ReflectionTestUtils.setField(connection, "id", CONNECTION_ID);
        return connection;
    }

    private Connection githubPatConnection() {
        Connection connection = new Connection(
                workspace, IntegrationKind.GITHUB, "pat", new ConnectionConfig.GitHubPatConfig("acme", null, Set.of()));
        ReflectionTestUtils.setField(connection, "id", CONNECTION_ID);
        return connection;
    }

    @Nested
    class Describe {

        @Test
        void appInstallationNotSuspended_webhookRegisteredTrueAndHealthy() {
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(Optional.of(githubAppConnection()));
            when(suspensionTracker.isInstallationMarkedSuspended(INSTALLATION_ID))
                    .thenReturn(false);

            ConnectionSyncDetails details = provider(schedulerProperties(false)).describe(ref, CONNECTION_ID);

            assertThat(details.webhookRegistered()).isTrue();
            assertThat(details.vendorHealthDegraded()).isFalse();
        }

        @Test
        void appInstallationSuspended_vendorHealthDegraded() {
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(Optional.of(githubAppConnection()));
            when(suspensionTracker.isInstallationMarkedSuspended(INSTALLATION_ID))
                    .thenReturn(true);

            ConnectionSyncDetails details = provider(schedulerProperties(false)).describe(ref, CONNECTION_ID);

            assertThat(details.vendorHealthDegraded()).isTrue();
        }

        @Test
        void patConnection_webhookRegisteredNull() {
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(Optional.of(githubPatConnection()));

            ConnectionSyncDetails details = provider(schedulerProperties(false)).describe(ref, CONNECTION_ID);

            assertThat(details.webhookRegistered()).isNull();
            assertThat(details.vendorHealthDegraded()).isFalse();
        }

        @Test
        void rateLimitSnapshot_passedThroughFromTracker() {
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(Optional.of(githubAppConnection()));
            RateLimitSnapshot snapshot =
                    new RateLimitSnapshot(5000, 4200, Instant.now().plusSeconds(1800), Instant.now(), null);
            when(rateLimitTracker.snapshot(WORKSPACE_ID)).thenReturn(snapshot);

            ConnectionSyncDetails details = provider(schedulerProperties(false)).describe(ref, CONNECTION_ID);

            assertThat(details.rateLimit()).isEqualTo(snapshot);
        }

        @Test
        void rateLimitNeverObserved_isNull() {
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(Optional.of(githubAppConnection()));

            ConnectionSyncDetails details = provider(schedulerProperties(false)).describe(ref, CONNECTION_ID);

            assertThat(details.rateLimit()).isNull();
        }

        @Test
        void nextScheduledSyncAt_isTheCronsNextOccurrence() {
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(Optional.of(githubAppConnection()));
            Instant before = Instant.now();

            ConnectionSyncDetails details = provider(schedulerProperties(false)).describe(ref, CONNECTION_ID);

            // The configured cron fires daily at 03:00: assert the actual next occurrence, not merely
            // "in the future" (which any future instant, e.g. now+1s, would satisfy).
            Instant next = details.nextScheduledSyncAt();
            assertNotNull(next);
            ZonedDateTime fire = next.atZone(ZoneId.systemDefault());
            assertThat(fire.getHour()).isEqualTo(3);
            assertThat(fire.getMinute()).isZero();
            assertThat(fire.getSecond()).isZero();
            // 25h, not 24h: on the DST fall-back day the gap to the next 03:00 fire stretches to 25h.
            assertThat(next).isAfter(before).isBeforeOrEqualTo(before.plus(Duration.ofHours(25)));
        }

        @Test
        void syncInterval_isTheCadenceOfTheConfiguredCron() {
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(Optional.of(githubAppConnection()));

            ConnectionSyncDetails details = provider(schedulerProperties(false)).describe(ref, CONNECTION_ID);

            // Without the cadence a "last synced 4h ago" reading is unjudgeable — callers cannot tell
            // stale from on-schedule. The configured cron fires daily, so the cadence is 24h.
            assertThat(details.syncInterval()).isEqualTo(Duration.ofHours(24));
            assertThat(details.nextScheduledSyncAt()).isNotNull();
        }

        @Test
        void nextScheduledSyncAt_invalidCron_isNull() {
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(Optional.of(githubAppConnection()));
            SyncSchedulerProperties broken = new SyncSchedulerProperties(
                    true,
                    7,
                    "not a cron",
                    15,
                    new BackfillProperties(false, 50, 100, 60),
                    new FilterProperties(Set.of(), Set.of(), Set.of()),
                    null,
                    null);

            assertThat(provider(broken).describe(ref, CONNECTION_ID).nextScheduledSyncAt())
                    .isNull();
        }

        @Test
        void backfillDisabled_stateIsDisabled() {
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(Optional.of(githubAppConnection()));

            ConnectionSyncDetails details = provider(schedulerProperties(false)).describe(ref, CONNECTION_ID);

            assertThat(details.backfill()).isEqualTo(new BackfillSummary("DISABLED", null));
        }

        @Test
        void backfillEnabledNoMonitoredRepos_stateIsNotStarted() {
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(Optional.of(githubAppConnection()));
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of());

            ConnectionSyncDetails details = provider(schedulerProperties(true)).describe(ref, CONNECTION_ID);

            assertNotNull(details.backfill());
            assertThat(details.backfill().state()).isEqualTo("NOT_STARTED");
            assertThat(details.backfill().percent()).isNull();
        }

        @Test
        void backfillEnabledAllComplete_stateIsCompleteAndPercentIsFull() {
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(Optional.of(githubAppConnection()));
            RepositoryToMonitor rtm = WorkspaceTestFixtures.repositoryMonitor(workspace, "acme/repo-a");
            rtm.setId(500L);
            rtm.setIssueBackfillHighWaterMark(100);
            rtm.setIssueBackfillCheckpoint(0);
            rtm.setPullRequestBackfillHighWaterMark(0);
            rtm.setPullRequestBackfillCheckpoint(0);
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(rtm));

            ConnectionSyncDetails details = provider(schedulerProperties(true)).describe(ref, CONNECTION_ID);

            assertNotNull(details.backfill());
            assertThat(details.backfill().state()).isEqualTo("COMPLETE");
            assertThat(details.backfill().percent()).isEqualTo(100);
        }

        @Test
        void backfillEnabledPartialProgress_stateIsInProgressWithCoarsePercent() {
            when(connectionRepository.findById(CONNECTION_ID)).thenReturn(Optional.of(githubAppConnection()));
            RepositoryToMonitor rtm = WorkspaceTestFixtures.repositoryMonitor(workspace, "acme/repo-a");
            rtm.setId(500L);
            rtm.setIssueBackfillHighWaterMark(100);
            rtm.setIssueBackfillCheckpoint(25);
            rtm.setPullRequestBackfillHighWaterMark(0);
            rtm.setPullRequestBackfillCheckpoint(0);
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(rtm));

            ConnectionSyncDetails details = provider(schedulerProperties(true)).describe(ref, CONNECTION_ID);

            assertNotNull(details.backfill());
            assertThat(details.backfill().state()).isEqualTo("IN_PROGRESS");
            assertThat(details.backfill().percent()).isEqualTo(75);
        }
    }

    @Nested
    class Resources {

        @Test
        void noMonitoredRepositories_returnsEmptyList() {
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of());

            List<SyncResourceState> resources =
                    provider(schedulerProperties(false)).resources(ref, CONNECTION_ID);

            assertThat(resources).isEmpty();
        }

        @Test
        void mapsRepositoryToMonitor_withItemCountFromGroupedQuery() {
            RepositoryToMonitor rtm = WorkspaceTestFixtures.repositoryMonitor(workspace, "acme/repo-a");
            rtm.setId(500L);
            rtm.setRecentSyncError("Issue sync: ABORTED_ERROR");
            rtm.setHistoricalBackfillSyncError("Historical issue backfill aborted");
            Instant issuesSyncedAt = Instant.now().minusSeconds(60);
            Instant prsSyncedAt = Instant.now().minusSeconds(30);
            rtm.setIssuesSyncedAt(issuesSyncedAt);
            rtm.setPullRequestsSyncedAt(prsSyncedAt);
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(rtm));

            Repository repository = new Repository();
            repository.setId(900L);
            repository.setNameWithOwner("acme/repo-a");
            when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenReturn(List.of(repository));

            // Headline itemCount is issues + pull requests: 30 + 12 = 42.
            when(countReader.countsByRepositoryId(anyCollection()))
                    .thenReturn(Map.of(900L, new ScmResourceCounts(30, 12, 0, 0, 0, 0)));

            List<SyncResourceState> resources =
                    provider(schedulerProperties(false)).resources(ref, CONNECTION_ID);

            assertThat(resources).hasSize(1);
            SyncResourceState resource = resources.get(0);
            assertThat(resource.id()).isEqualTo(500L);
            assertThat(resource.externalId()).isEqualTo("acme/repo-a");
            assertThat(resource.type()).isEqualTo(SyncResourceState.Type.REPOSITORY);
            assertThat(resource.state()).isEqualTo("SYNCED");
            assertThat(resource.lastSyncedAt()).isEqualTo(prsSyncedAt);
            assertThat(resource.itemCount()).isEqualTo(42L);
            assertThat(resource.upstreamCount()).isNull();
            assertThat(resource.lastError()).isEqualTo("Issue sync: ABORTED_ERROR; Historical issue backfill aborted");
        }

        @Test
        void repositoryPendingInitialSync_stateReflectsPending() {
            RepositoryToMonitor rtm = WorkspaceTestFixtures.repositoryMonitor(workspace, "acme/repo-b");
            rtm.setId(501L);
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(rtm));
            when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenReturn(List.of());

            List<SyncResourceState> resources =
                    provider(schedulerProperties(false)).resources(ref, CONNECTION_ID);

            assertThat(resources).hasSize(1);
            assertThat(resources.get(0).state()).isEqualTo("PENDING");
            assertThat(resources.get(0).itemCount()).isNull();
            assertThat(resources.get(0).lastSyncedAt()).isNull();
        }

        @Test
        void perEntityClassBreakdown_carriesAllSixClassesAndRollsUpToItemCount() {
            RepositoryToMonitor rtm = WorkspaceTestFixtures.repositoryMonitor(workspace, "acme/repo-a");
            rtm.setId(500L);
            rtm.setIssuesSyncedAt(Instant.now().minusSeconds(60));
            rtm.setPullRequestsSyncedAt(Instant.now().minusSeconds(30));
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(rtm));

            Repository repository = new Repository();
            repository.setId(900L);
            repository.setNameWithOwner("acme/repo-a");
            when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenReturn(List.of(repository));
            when(countReader.countsByRepositoryId(anyCollection()))
                    .thenReturn(Map.of(900L, new ScmResourceCounts(30, 12, 13, 14, 15, 16)));

            List<SyncResourceState> resources =
                    provider(schedulerProperties(false)).resources(ref, CONNECTION_ID);

            SyncResourceState resource = resources.get(0);
            assertThat(resource.counts())
                    .extracting(SyncResourceCount::key, SyncResourceCount::count)
                    .containsExactly(
                            tuple("issues", 30L),
                            tuple("pullRequests", 12L),
                            tuple("issueComments", 13L),
                            tuple("reviews", 14L),
                            tuple("reviewComments", 15L),
                            tuple("commits", 16L));
            // The headline must stay the rollup of only the classes the sync calls "items" — the
            // comment/review/commit counts are much larger and would visibly inflate it if included.
            assertThat(resource.itemCount()).isEqualTo(42L);
        }

        @Test
        void issuesStalledWhilePullRequestsFresh_watermarksAreReportedPerClassNotCollapsed() {
            Instant issuesSyncedAt = Instant.now().minus(Duration.ofDays(4));
            Instant prsSyncedAt = Instant.now().minusSeconds(30);
            RepositoryToMonitor rtm = WorkspaceTestFixtures.repositoryMonitor(workspace, "acme/repo-a");
            rtm.setId(500L);
            rtm.setIssuesSyncedAt(issuesSyncedAt);
            rtm.setPullRequestsSyncedAt(prsSyncedAt);
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(rtm));

            Repository repository = new Repository();
            repository.setId(900L);
            repository.setNameWithOwner("acme/repo-a");
            when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenReturn(List.of(repository));
            when(countReader.countsByRepositoryId(anyCollection()))
                    .thenReturn(Map.of(900L, new ScmResourceCounts(30, 12, 0, 0, 0, 0)));

            List<SyncResourceState> resources =
                    provider(schedulerProperties(false)).resources(ref, CONNECTION_ID);

            List<SyncResourceCount> counts = resources.get(0).counts();
            SyncResourceCount issues = counts.stream()
                    .filter(c -> c.key().equals("issues"))
                    .findFirst()
                    .orElseThrow();
            SyncResourceCount pullRequests = counts.stream()
                    .filter(c -> c.key().equals("pullRequests"))
                    .findFirst()
                    .orElseThrow();

            // This is the point of the breakdown: issues stopped four days ago while PRs kept syncing.
            // The row-level lastSyncedAt collapses both to the newest (latestNonNull) and shows green —
            // only the per-class watermarks make the stall visible.
            assertThat(issues.lastSyncedAt()).isEqualTo(issuesSyncedAt);
            assertThat(issues.lastSyncedAt()).isNotEqualTo(prsSyncedAt);
            assertThat(pullRequests.lastSyncedAt()).isEqualTo(prsSyncedAt);
            assertThat(resources.get(0).lastSyncedAt()).isEqualTo(prsSyncedAt);
        }

        @Test
        void monitorWithoutLocalRepositoryRow_reportsNoBreakdownRatherThanZeroes() {
            RepositoryToMonitor rtm = WorkspaceTestFixtures.repositoryMonitor(workspace, "acme/repo-b");
            rtm.setId(501L);
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(rtm));
            when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenReturn(List.of());

            List<SyncResourceState> resources =
                    provider(schedulerProperties(false)).resources(ref, CONNECTION_ID);

            // Never-synced must stay distinct from synced-zero: six zero rows would assert we looked and
            // found nothing, when in fact we never looked.
            assertThat(resources.get(0).itemCount()).isNull();
            assertThat(resources.get(0).counts()).isEmpty();
        }

        @Test
        void backfillInProgress_percentReflectsRemainingAgainstHighWaterMark() {
            RepositoryToMonitor rtm = WorkspaceTestFixtures.repositoryMonitor(workspace, "acme/repo-c");
            rtm.setId(502L);
            rtm.setIssuesSyncedAt(Instant.now());
            rtm.setPullRequestsSyncedAt(Instant.now());
            rtm.setIssueBackfillHighWaterMark(100);
            rtm.setIssueBackfillCheckpoint(25);
            rtm.setPullRequestBackfillHighWaterMark(0);
            rtm.setPullRequestBackfillCheckpoint(0);
            when(repositoryToMonitorRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(rtm));
            when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenReturn(List.of());

            List<SyncResourceState> resources =
                    provider(schedulerProperties(false)).resources(ref, CONNECTION_ID);

            assertThat(resources).hasSize(1);
            assertThat(resources.get(0).backfillPercent()).isEqualTo(75);
            assertThat(resources.get(0).backfillCompletedThrough()).isNull();
        }
    }
}
