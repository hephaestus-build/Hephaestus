package de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.BackfillProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.FilterProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.BackfillStateProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncPass;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetTestBuilder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreview.GitHubPullRequestReviewProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreview.GitHubPullRequestReviewSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment.GitHubPullRequestReviewCommentSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill.GitHubHistoricalBackfillService.BackfillCycleResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill.GitHubHistoricalBackfillService.BackfillProgress;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unit tests for {@link GitHubHistoricalBackfillService}.
 *
 * <p>Tests the backfill orchestration logic including enable/disable gating,
 * per-repository skip conditions (incremental sync pending, cooldown, rate limits,
 * already complete), and progress tracking records.
 */
class GitHubHistoricalBackfillServiceTest extends BaseUnitTest {

    @Mock
    private SyncTargetProvider syncTargetProvider;

    @Mock
    private BackfillStateProvider backfillStateProvider;

    @Mock
    private GitHubGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitHubIssueProcessor issueProcessor;

    @Mock
    private GitHubIssueCommentProcessor issueCommentProcessor;

    @Mock
    private GitHubPullRequestProcessor prProcessor;

    @Mock
    private GitHubPullRequestReviewProcessor reviewProcessor;

    @Mock
    private GitHubPullRequestReviewSyncService reviewSyncService;

    @Mock
    private GitHubPullRequestReviewCommentSyncService reviewCommentSyncService;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private HttpGraphQlClient client;

    private GitHubHistoricalBackfillService service = mock(GitHubHistoricalBackfillService.class);

    private static final Long SCOPE_ID = 100L;
    private static final Long INSTALLATION_ID = 200L;
    private static final Long SYNC_TARGET_ID_A = 1L;
    private static final Long SYNC_TARGET_ID_B = 2L;
    private static final Long SYNC_TARGET_ID_C = 3L;

    private GitHubSyncProperties syncProperties;
    private SyncSchedulerProperties enabledSchedulerProperties;
    private SyncSchedulerProperties disabledSchedulerProperties;

    @BeforeEach
    void setUp() {
        syncProperties = new GitHubSyncProperties(
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                Duration.ofSeconds(120),
                Duration.ZERO, // no throttle in tests
                true,
                Duration.ofMinutes(5),
                10);

        enabledSchedulerProperties = new SyncSchedulerProperties(
                true,
                7,
                "0 0 3 * * *",
                15,
                new BackfillProperties(true, 50, 100, 60),
                new FilterProperties(Set.of(), Set.of(), Set.of()),
                null,
                null);

        disabledSchedulerProperties = new SyncSchedulerProperties(
                true,
                7,
                "0 0 3 * * *",
                15,
                new BackfillProperties(false, 50, 100, 60),
                new FilterProperties(Set.of(), Set.of(), Set.of()),
                null,
                null);

        // TransactionTemplate: execute callbacks immediately
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    private GitHubHistoricalBackfillService createService(SyncSchedulerProperties schedulerProps) {
        return new GitHubHistoricalBackfillService(
                syncTargetProvider,
                backfillStateProvider,
                schedulerProps,
                syncProperties,
                graphQlClientProvider,
                issueProcessor,
                issueCommentProcessor,
                prProcessor,
                reviewProcessor,
                reviewSyncService,
                reviewCommentSyncService,
                repositoryRepository,
                transactionTemplate,
                Runnable::run // synchronous executor for tests
                );
    }

    private SyncTarget createTargetWithIncrementalComplete(Long id, String repoName) {
        // Incremental sync completed; backfill high-water marks left null (not initialized).
        return SyncTargetTestBuilder.syncTarget()
                .id(id)
                .scopeId(SCOPE_ID)
                .installationId(INSTALLATION_ID)
                .authMode(AuthMode.INSTALLATION_APP)
                .repositoryNameWithOwner(repoName)
                .lastLabelsSyncedAt(Instant.now())
                .lastMilestonesSyncedAt(Instant.now())
                .lastIssuesSyncedAt(Instant.now())
                .lastPullRequestsSyncedAt(Instant.now())
                .lastCollaboratorsSyncedAt(Instant.now())
                .lastFullSyncAt(Instant.now())
                .build();
    }

    private SyncTarget createTargetPendingIncrementalSync(Long id, String repoName) {
        // All sync timestamps null => incremental sync NOT completed.
        return SyncTargetTestBuilder.syncTarget()
                .id(id)
                .scopeId(SCOPE_ID)
                .installationId(INSTALLATION_ID)
                .authMode(AuthMode.INSTALLATION_APP)
                .repositoryNameWithOwner(repoName)
                .build();
    }

    private SyncTarget createTargetWithBackfillComplete(Long id, String repoName) {
        // Checkpoints at 0 => backfill complete.
        return SyncTargetTestBuilder.syncTarget()
                .id(id)
                .scopeId(SCOPE_ID)
                .installationId(INSTALLATION_ID)
                .authMode(AuthMode.INSTALLATION_APP)
                .repositoryNameWithOwner(repoName)
                .lastLabelsSyncedAt(Instant.now())
                .lastMilestonesSyncedAt(Instant.now())
                .lastIssuesSyncedAt(Instant.now())
                .lastPullRequestsSyncedAt(Instant.now())
                .lastCollaboratorsSyncedAt(Instant.now())
                .lastFullSyncAt(Instant.now())
                .issueBackfillHighWaterMark(100)
                .issueBackfillCheckpoint(0)
                .pullRequestBackfillHighWaterMark(50)
                .pullRequestBackfillCheckpoint(0)
                .backfillLastRunAt(Instant.now())
                .build();
    }

    private SyncTarget createTargetWithBackfillInProgress(Long id, String repoName) {
        // Checkpoints between 0 and high-water mark => backfill in progress.
        return SyncTargetTestBuilder.syncTarget()
                .id(id)
                .scopeId(SCOPE_ID)
                .installationId(INSTALLATION_ID)
                .authMode(AuthMode.INSTALLATION_APP)
                .repositoryNameWithOwner(repoName)
                .lastLabelsSyncedAt(Instant.now())
                .lastMilestonesSyncedAt(Instant.now())
                .lastIssuesSyncedAt(Instant.now())
                .lastPullRequestsSyncedAt(Instant.now())
                .lastCollaboratorsSyncedAt(Instant.now())
                .lastFullSyncAt(Instant.now())
                .issueBackfillHighWaterMark(100)
                .issueBackfillCheckpoint(42)
                .pullRequestBackfillHighWaterMark(50)
                .pullRequestBackfillCheckpoint(25)
                .backfillLastRunAt(Instant.now().minusSeconds(120))
                .issueSyncCursor("cursor-issue-page-3")
                .pullRequestSyncCursor("cursor-pr-page-2")
                .build();
    }

    private SyncSession createSession(List<SyncTarget> targets) {
        return new SyncSession(
                SCOPE_ID,
                "test-workspace",
                "Test Workspace",
                "test-org",
                INSTALLATION_ID,
                null, // serverUrl — GitHub flow doesn't use it; GitLab tests pass the workspace URL
                targets,
                new SyncContextProvider.SyncContext(SCOPE_ID, "test-workspace", "Test Workspace", INSTALLATION_ID));
    }

    @Nested
    class IsEnabled {

        @Test
        void shouldReturnTrueWhenBackfillEnabled() {
            service = createService(enabledSchedulerProperties);

            assertThat(service.isEnabled()).isTrue();
        }

        @Test
        void shouldReturnFalseWhenBackfillDisabled() {
            service = createService(disabledSchedulerProperties);

            assertThat(service.isEnabled()).isFalse();
        }
    }

    @Nested
    class RunBackfillCycle {

        @Test
        void shouldReturnNothingToDoWhenDisabled() {
            service = createService(disabledSchedulerProperties);

            BackfillCycleResult result = service.runBackfillCycle();

            assertThat(result.repositoriesProcessed()).isZero();
            assertThat(result.pendingRepositories()).isZero();
            assertThat(result.skipReason()).isNull();
        }

        @Test
        void shouldReturnNothingToDoWhenNoSessions() {
            service = createService(enabledSchedulerProperties);
            when(syncTargetProvider.getSyncSessions(IntegrationKind.GITHUB)).thenReturn(List.of());

            BackfillCycleResult result = service.runBackfillCycle();

            assertThat(result.repositoriesProcessed()).isZero();
            assertThat(result.pendingRepositories()).isZero();
            assertThat(result.skipReason()).isNull();
        }

        @Test
        void shouldSkipScopeWhenRateLimitBelowThreshold() {
            service = createService(enabledSchedulerProperties);
            SyncTarget target = createTargetWithIncrementalComplete(SYNC_TARGET_ID_A, "org/repo-a");
            SyncSession session = createSession(List.of(target));
            when(syncTargetProvider.getSyncSessions(IntegrationKind.GITHUB)).thenReturn(List.of(session));
            // Rate limit below threshold (100)
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(50);
            when(graphQlClientProvider.getRateLimitResetAt(SCOPE_ID))
                    .thenReturn(Instant.now().plusSeconds(300));

            BackfillCycleResult result = service.runBackfillCycle();

            assertThat(result.repositoriesProcessed()).isZero();
            assertThat(result.pendingRepositories()).isEqualTo(1);
        }

        @Test
        void shouldSkipScopeWhenRateLimitBelowThresholdButNotCountCompleteRepos() {
            service = createService(enabledSchedulerProperties);
            SyncTarget completeTarget = createTargetWithBackfillComplete(SYNC_TARGET_ID_A, "org/repo-a");
            SyncTarget incompleteTarget = createTargetWithIncrementalComplete(SYNC_TARGET_ID_B, "org/repo-b");
            SyncSession session = createSession(List.of(completeTarget, incompleteTarget));
            when(syncTargetProvider.getSyncSessions(IntegrationKind.GITHUB)).thenReturn(List.of(session));
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(50);
            when(graphQlClientProvider.getRateLimitResetAt(SCOPE_ID))
                    .thenReturn(Instant.now().plusSeconds(300));

            BackfillCycleResult result = service.runBackfillCycle();

            assertThat(result.repositoriesProcessed()).isZero();
            assertThat(result.pendingRepositories()).isEqualTo(1);
        }

        @Test
        void shouldSkipRepoWhenBackfillAlreadyComplete() {
            service = createService(enabledSchedulerProperties);
            SyncTarget completeTarget = createTargetWithBackfillComplete(SYNC_TARGET_ID_A, "org/repo-a");
            SyncSession session = createSession(List.of(completeTarget));
            when(syncTargetProvider.getSyncSessions(IntegrationKind.GITHUB)).thenReturn(List.of(session));
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(5000);

            BackfillCycleResult result = service.runBackfillCycle();

            // Complete repos are silently skipped: no work, no pending.
            assertThat(result.repositoriesProcessed()).isZero();
            assertThat(result.pendingRepositories()).isZero();
        }

        @Test
        void shouldSkipRepoWhenIncrementalSyncPendingAndCountAsPending() {
            service = createService(enabledSchedulerProperties);
            SyncTarget pendingTarget = createTargetPendingIncrementalSync(SYNC_TARGET_ID_A, "org/repo-a");
            SyncSession session = createSession(List.of(pendingTarget));
            when(syncTargetProvider.getSyncSessions(IntegrationKind.GITHUB)).thenReturn(List.of(session));
            // Rate limit is checked before per-repo iteration; stub it above threshold
            // so we reach the per-repo incremental sync gate
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(5000);

            BackfillCycleResult result = service.runBackfillCycle();

            // Skipped per-repo because lastIssuesAndPullRequestsSyncedAt == null.
            assertThat(result.repositoriesProcessed()).isZero();
            assertThat(result.pendingRepositories()).isEqualTo(1);
        }

        @Test
        void shouldSkipEntireScopeWhenAnyRepoPendingIncrementalSync() {
            service = createService(enabledSchedulerProperties);

            SyncTarget repoA = createTargetWithIncrementalComplete(SYNC_TARGET_ID_A, "org/repo-a");
            SyncTarget repoB = createTargetPendingIncrementalSync(SYNC_TARGET_ID_B, "org/repo-b");
            SyncSession session = createSession(List.of(repoA, repoB));

            when(syncTargetProvider.getSyncSessions(IntegrationKind.GITHUB)).thenReturn(List.of(session));
            // Rate limit is always checked; stub above threshold to reach per-repo iteration
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(5000);
            // repoA passes incremental check, enters backfillRepository but not found in DB
            when(repositoryRepository.findByNameWithOwner("org/repo-a")).thenReturn(Optional.empty());

            BackfillCycleResult result = service.runBackfillCycle();

            // repoA: incremental done, attempted backfill but not in DB (not counted).
            // repoB: skipped per-repo due to pending incremental sync (counted as pending).
            assertThat(result.pendingRepositories()).isEqualTo(1);
            assertThat(result.repositoriesProcessed()).isZero();
        }

        @Test
        void shouldBreakScopeLoopWhenRateLimitDropsBelowThresholdMidLoop() {
            service = createService(enabledSchedulerProperties);

            SyncTarget repoA = createTargetWithIncrementalComplete(SYNC_TARGET_ID_A, "org/repo-a");
            SyncTarget repoB = createTargetWithIncrementalComplete(SYNC_TARGET_ID_B, "org/repo-b");
            SyncSession session = createSession(List.of(repoA, repoB));

            when(syncTargetProvider.getSyncSessions(IntegrationKind.GITHUB)).thenReturn(List.of(session));
            // First call: above threshold (scope-level check passes)
            // Second call (re-check before repo A): still above threshold
            // Third call (re-check before repo B): below threshold
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID))
                    .thenReturn(5000)
                    .thenReturn(5000)
                    .thenReturn(50);
            // Note: forScope is NOT called because backfillRepository returns false
            // (repo not in DB) before reaching the GraphQL client
            when(repositoryRepository.findByNameWithOwner("org/repo-a")).thenReturn(Optional.empty());

            BackfillCycleResult result = service.runBackfillCycle();

            assertThat(result.pendingRepositories()).isEqualTo(1);
        }
    }

    @Nested
    class BackfillRepository {

        @BeforeEach
        void setUpService() {
            service = createService(enabledSchedulerProperties);
        }

        @Test
        void shouldReturnFalseWhenRepoNameIsInvalid() {
            SyncTarget target = SyncTargetTestBuilder.syncTarget()
                    .id(SYNC_TARGET_ID_A)
                    .scopeId(SCOPE_ID)
                    .installationId(INSTALLATION_ID)
                    .authMode(AuthMode.INSTALLATION_APP)
                    .repositoryNameWithOwner("invalid-repo-name") // no owner/name separator
                    .lastIssuesSyncedAt(Instant.now())
                    .lastPullRequestsSyncedAt(Instant.now())
                    .build();

            boolean result = service.backfillRepository(target, 50, BackfillPageObserver.NOOP);

            assertThat(result).isFalse();
        }

        @Test
        void shouldReturnFalseWhenRepositoryNotInDatabase() {
            SyncTarget target = createTargetWithIncrementalComplete(SYNC_TARGET_ID_A, "org/repo-a");
            when(repositoryRepository.findByNameWithOwner("org/repo-a")).thenReturn(Optional.empty());

            boolean result = service.backfillRepository(target, 50, BackfillPageObserver.NOOP);

            assertThat(result).isFalse();
            verify(graphQlClientProvider, never()).forScope(anyLong());
        }
    }

    @Nested
    class BackfillCycleResultTests {

        @Test
        void shouldCreateNothingToDoResult() {
            BackfillCycleResult result = BackfillCycleResult.nothingToDo();

            assertThat(result.repositoriesProcessed()).isZero();
            assertThat(result.pendingRepositories()).isZero();
            assertThat(result.skipReason()).isNull();
        }

        @Test
        void shouldCreateResultWithValues() {
            BackfillCycleResult result = new BackfillCycleResult(5, 3, "rateLimitLow");

            assertThat(result.repositoriesProcessed()).isEqualTo(5);
            assertThat(result.pendingRepositories()).isEqualTo(3);
            assertThat(result.skipReason()).isEqualTo("rateLimitLow");
        }
    }

    @Nested
    class BackfillProgressTests {

        @Test
        void shouldCreateFromSyncTargetNotStarted() {
            SyncTarget target = createTargetWithIncrementalComplete(SYNC_TARGET_ID_A, "org/repo-a");

            BackfillProgress progress = BackfillProgress.fromSyncTarget(target);

            assertThat(progress.repositoryName()).isEqualTo("org/repo-a");
            assertThat(progress.isInitialized()).isFalse();
            assertThat(progress.isComplete()).isFalse();
            assertThat(progress.lastRunAt()).isNull();
            assertThat(progress.issueCursor()).isNull();
            assertThat(progress.prCursor()).isNull();
        }

        @Test
        void shouldCreateFromSyncTargetComplete() {
            SyncTarget target = createTargetWithBackfillComplete(SYNC_TARGET_ID_A, "org/repo-a");

            BackfillProgress progress = BackfillProgress.fromSyncTarget(target);

            assertThat(progress.repositoryName()).isEqualTo("org/repo-a");
            assertThat(progress.isInitialized()).isTrue();
            assertThat(progress.isComplete()).isTrue();
            assertThat(progress.lastRunAt()).isNotNull();
        }

        @Test
        void shouldCreateFromSyncTargetInProgress() {
            SyncTarget target = createTargetWithBackfillInProgress(SYNC_TARGET_ID_A, "org/repo-a");

            BackfillProgress progress = BackfillProgress.fromSyncTarget(target);

            assertThat(progress.repositoryName()).isEqualTo("org/repo-a");
            assertThat(progress.isInitialized()).isTrue();
            assertThat(progress.isComplete()).isFalse();
            assertThat(progress.lastRunAt()).isNotNull();
            assertThat(progress.issueCursor()).isEqualTo("cursor-issue-page-3");
            assertThat(progress.prCursor()).isEqualTo("cursor-pr-page-2");
        }

        @Test
        void shouldReturnCompleteSummary() {
            SyncTarget target = createTargetWithBackfillComplete(SYNC_TARGET_ID_A, "org/repo-a");
            BackfillProgress progress = BackfillProgress.fromSyncTarget(target);

            String summary = progress.summary();

            assertThat(summary).isEqualTo("Backfill complete for org/repo-a");
        }

        @Test
        void shouldReturnNotStartedSummary() {
            SyncTarget target = createTargetWithIncrementalComplete(SYNC_TARGET_ID_A, "org/repo-a");
            BackfillProgress progress = BackfillProgress.fromSyncTarget(target);

            String summary = progress.summary();

            assertThat(summary).isEqualTo("Backfill not started for org/repo-a");
        }

        @Test
        void shouldReturnInProgressSummary() {
            SyncTarget target = createTargetWithBackfillInProgress(SYNC_TARGET_ID_A, "org/repo-a");
            BackfillProgress progress = BackfillProgress.fromSyncTarget(target);

            String summary = progress.summary();

            assertThat(summary).startsWith("Backfill in progress for org/repo-a (last run: ");
        }

        @Test
        void percentComplete_notStarted_isNull() {
            SyncTarget target = createTargetWithIncrementalComplete(SYNC_TARGET_ID_A, "org/repo-a");

            BackfillProgress progress = BackfillProgress.fromSyncTarget(target);

            assertThat(progress.percentComplete()).isNull();
        }

        @Test
        void percentComplete_complete_isFullHundred() {
            SyncTarget target = createTargetWithBackfillComplete(SYNC_TARGET_ID_A, "org/repo-a");

            BackfillProgress progress = BackfillProgress.fromSyncTarget(target);

            assertThat(progress.percentComplete()).isEqualTo(100);
        }

        @Test
        void percentComplete_inProgress_reflectsRemainingAgainstHighWaterMarkTotal() {
            // issueHWM=100, issueCheckpoint(remaining)=42, prHWM=50, prCheckpoint(remaining)=25
            // total=150, remaining=67 -> done=83 -> 83/150 = 55.33% rounds to 55
            SyncTarget target = createTargetWithBackfillInProgress(SYNC_TARGET_ID_A, "org/repo-a");

            BackfillProgress progress = BackfillProgress.fromSyncTarget(target);

            assertThat(progress.itemsTotal()).isEqualTo(150);
            assertThat(progress.itemsRemaining()).isEqualTo(67);
            assertThat(progress.percentComplete()).isEqualTo(55);
        }

        @Test
        void percentComplete_boundaries_emptyAndCompleteTotals() {
            assertThat(BackfillProgress.percentComplete(true, 25, 100)).isEqualTo(75);
            assertThat(BackfillProgress.percentComplete(false, 0, 0)).isNull();
            assertThat(BackfillProgress.percentComplete(true, 0, 0)).isEqualTo(100);
        }
    }

    @Nested
    class SyncTargetBackfillState {

        @Test
        void shouldReportBackfillNotInitializedWhenNoHighWaterMark() {
            SyncTarget target = createTargetWithIncrementalComplete(SYNC_TARGET_ID_A, "org/repo-a");

            assertThat(target.isIssueBackfillInitialized()).isFalse();
            assertThat(target.isPullRequestBackfillInitialized()).isFalse();
            assertThat(target.isBackfillInitialized()).isFalse();
            assertThat(target.isBackfillComplete()).isFalse();
        }

        @Test
        void shouldReportBackfillCompleteWhenCheckpointsAreZero() {
            SyncTarget target = createTargetWithBackfillComplete(SYNC_TARGET_ID_A, "org/repo-a");

            assertThat(target.isIssueBackfillInitialized()).isTrue();
            assertThat(target.isPullRequestBackfillInitialized()).isTrue();
            assertThat(target.isBackfillInitialized()).isTrue();
            assertThat(target.isIssueBackfillComplete()).isTrue();
            assertThat(target.isPullRequestBackfillComplete()).isTrue();
            assertThat(target.isBackfillComplete()).isTrue();
        }

        @Test
        void shouldReportBackfillInProgressWhenCheckpointsNonZero() {
            SyncTarget target = createTargetWithBackfillInProgress(SYNC_TARGET_ID_A, "org/repo-a");

            assertThat(target.isBackfillInitialized()).isTrue();
            assertThat(target.isBackfillComplete()).isFalse();
            assertThat(target.getIssueBackfillRemaining()).isEqualTo(42);
            assertThat(target.getPullRequestBackfillRemaining()).isEqualTo(25);
            assertThat(target.getBackfillRemaining()).isEqualTo(67);
        }

        @Test
        void shouldReportZeroRemainingWhenNotInitialized() {
            SyncTarget target = createTargetWithIncrementalComplete(SYNC_TARGET_ID_A, "org/repo-a");

            assertThat(target.getIssueBackfillRemaining()).isZero();
            assertThat(target.getPullRequestBackfillRemaining()).isZero();
            assertThat(target.getBackfillRemaining()).isZero();
        }

        @Test
        void shouldReportZeroRemainingWhenHighWaterMarkIsZero() {
            SyncTarget target = SyncTargetTestBuilder.syncTarget()
                    .id(SYNC_TARGET_ID_A)
                    .scopeId(SCOPE_ID)
                    .installationId(INSTALLATION_ID)
                    .authMode(AuthMode.INSTALLATION_APP)
                    .repositoryNameWithOwner("org/empty-repo")
                    .lastIssuesSyncedAt(Instant.now())
                    .lastPullRequestsSyncedAt(Instant.now())
                    .issueBackfillHighWaterMark(0) // no issues
                    .issueBackfillCheckpoint(0)
                    .pullRequestBackfillHighWaterMark(0) // no PRs
                    .pullRequestBackfillCheckpoint(0)
                    .backfillLastRunAt(Instant.now())
                    .build();

            assertThat(target.isIssueBackfillComplete()).isTrue();
            assertThat(target.isPullRequestBackfillComplete()).isTrue();
            assertThat(target.isBackfillComplete()).isTrue();
            assertThat(target.getIssueBackfillRemaining()).isZero();
            assertThat(target.getPullRequestBackfillRemaining()).isZero();
        }

        @Test
        void shouldReportHighWaterMarkAsRemainingWhenCheckpointIsNull() {
            // Initialized but checkpoint not yet set (first batch hasn't completed).
            SyncTarget target = SyncTargetTestBuilder.syncTarget()
                    .id(SYNC_TARGET_ID_A)
                    .scopeId(SCOPE_ID)
                    .installationId(INSTALLATION_ID)
                    .authMode(AuthMode.INSTALLATION_APP)
                    .repositoryNameWithOwner("org/repo-a")
                    .lastIssuesSyncedAt(Instant.now())
                    .lastPullRequestsSyncedAt(Instant.now())
                    .issueBackfillHighWaterMark(200)
                    .pullRequestBackfillHighWaterMark(80)
                    // checkpoints left null (not yet set)
                    .build();

            assertThat(target.getIssueBackfillRemaining()).isEqualTo(200);
            assertThat(target.getPullRequestBackfillRemaining()).isEqualTo(80);
            assertThat(target.getBackfillRemaining()).isEqualTo(280);
        }
    }

    @Nested
    class GetProgress {

        @Test
        void shouldReturnProgressWhenTargetExists() {
            service = createService(enabledSchedulerProperties);
            SyncTarget target = createTargetWithBackfillInProgress(SYNC_TARGET_ID_A, "org/repo-a");
            when(syncTargetProvider.findSyncTargetById(SYNC_TARGET_ID_A)).thenReturn(Optional.of(target));

            Optional<BackfillProgress> progress = service.getProgress(SYNC_TARGET_ID_A);

            assertThat(progress).isPresent();
            assertThat(progress.get().repositoryName()).isEqualTo("org/repo-a");
            assertThat(progress.get().isInitialized()).isTrue();
            assertThat(progress.get().isComplete()).isFalse();
        }

        @Test
        void shouldReturnEmptyWhenTargetNotFound() {
            service = createService(enabledSchedulerProperties);
            when(syncTargetProvider.findSyncTargetById(999L)).thenReturn(Optional.empty());

            Optional<BackfillProgress> progress = service.getProgress(999L);

            assertThat(progress).isEmpty();
        }
    }

    // runBackfillBatch — the manual backfill sync-job runner's per-repository step

    @Nested
    class RunBackfillBatch {

        @Test
        void shouldRecordBackfillFailureOnTheMonitoredRepository() {
            service = createService(enabledSchedulerProperties);
            SyncTarget target = createTargetWithBackfillInProgress(SYNC_TARGET_ID_A, "org/repo-a");
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(1000);
            when(repositoryRepository.findByNameWithOwner("org/repo-a"))
                    .thenThrow(new IllegalStateException("sensitive database detail"));

            assertThat(service.runBackfillBatch(target, 50, BackfillPageObserver.NOOP))
                    .isFalse();

            verify(syncTargetProvider)
                    .updateSyncError(
                            SYNC_TARGET_ID_A,
                            SyncPass.HISTORICAL_BACKFILL,
                            "Historical backfill failed (IllegalStateException)");
        }

        @Test
        void alreadyComplete_returnsFalseWithoutCheckingRateLimit() {
            service = createService(enabledSchedulerProperties);
            SyncTarget target = createTargetWithBackfillComplete(SYNC_TARGET_ID_A, "org/repo-a");

            boolean didWork = service.runBackfillBatch(target, 50, BackfillPageObserver.NOOP);

            assertThat(didWork).isFalse();
            verify(graphQlClientProvider, never()).getRateLimitRemaining(any());
        }

        @Test
        void rateLimitBelowThreshold_returnsFalseWithoutAttemptingBackfill() {
            service = createService(enabledSchedulerProperties); // rateLimitThreshold = 100
            SyncTarget target = createTargetWithBackfillInProgress(SYNC_TARGET_ID_A, "org/repo-a");
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(50);

            boolean didWork = service.runBackfillBatch(target, 50, BackfillPageObserver.NOOP);

            assertThat(didWork).isFalse();
        }

        @Test
        @DisplayName(
                "gates apply even when isEnabled()=false — a manual trigger is the point when the scheduled cycle is off")
        void backfillDisabledGlobally_completeGateStillShortCircuits() {
            service = createService(disabledSchedulerProperties);
            SyncTarget target = createTargetWithBackfillComplete(SYNC_TARGET_ID_A, "org/repo-a");

            boolean didWork = service.runBackfillBatch(target, 50, BackfillPageObserver.NOOP);

            assertThat(service.isEnabled()).isFalse();
            assertThat(didWork).isFalse();
        }
    }

    @Nested
    class MultipleSessions {

        @Test
        void shouldProcessMultipleSessionsIndependently() {
            service = createService(enabledSchedulerProperties);

            Long scopeId2 = 200L;
            Long installationId2 = 300L;

            SyncTarget targetInScope1 = createTargetPendingIncrementalSync(SYNC_TARGET_ID_A, "org/repo-a");
            SyncTarget targetInScope2 = SyncTargetTestBuilder.syncTarget()
                    .id(SYNC_TARGET_ID_B)
                    .scopeId(scopeId2)
                    .installationId(installationId2)
                    .authMode(AuthMode.INSTALLATION_APP)
                    .repositoryNameWithOwner("org2/repo-b")
                    .build();

            SyncSession session1 = createSession(List.of(targetInScope1));
            SyncSession session2 = new SyncSession(
                    scopeId2,
                    "workspace-2",
                    "Workspace 2",
                    "org2",
                    installationId2,
                    null,
                    List.of(targetInScope2),
                    new SyncContextProvider.SyncContext(scopeId2, "workspace-2", "Workspace 2", installationId2));

            when(syncTargetProvider.getSyncSessions(IntegrationKind.GITHUB)).thenReturn(List.of(session1, session2));
            // Rate limit is always checked per scope; stub above threshold to reach per-repo iteration
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(5000);
            when(graphQlClientProvider.getRateLimitRemaining(scopeId2)).thenReturn(5000);

            BackfillCycleResult result = service.runBackfillCycle();

            // Both repos have pending incremental sync, so they are skipped per-repo and counted
            // as pending.
            assertThat(result.repositoriesProcessed()).isZero();
            assertThat(result.pendingRepositories()).isEqualTo(2);
        }
    }

    @Nested
    class MixedTargetStates {

        @Test
        @DisplayName("Should skip pending-incremental repos per-repo and process eligible ones")
        void shouldHandleMixedStatesCorrectly() {
            service = createService(enabledSchedulerProperties);

            SyncTarget completeRepo = createTargetWithBackfillComplete(SYNC_TARGET_ID_A, "org/repo-complete");
            SyncTarget pendingRepo = createTargetPendingIncrementalSync(SYNC_TARGET_ID_B, "org/repo-pending");
            SyncTarget eligibleRepo = createTargetWithIncrementalComplete(SYNC_TARGET_ID_C, "org/repo-eligible");

            SyncSession session = createSession(List.of(completeRepo, pendingRepo, eligibleRepo));
            when(syncTargetProvider.getSyncSessions(IntegrationKind.GITHUB)).thenReturn(List.of(session));
            // Rate limit is always checked; stub above threshold to reach per-repo iteration
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(5000);
            // eligibleRepo passes incremental check, enters backfillRepository but not in DB
            when(repositoryRepository.findByNameWithOwner("org/repo-eligible")).thenReturn(Optional.empty());

            BackfillCycleResult result = service.runBackfillCycle();

            // completeRepo: backfill complete, silently skipped (not counted).
            // pendingRepo: incremental sync pending, skipped per-repo, counted as pending.
            // eligibleRepo: attempted backfill but not in DB, returns false (not counted).
            assertThat(result.pendingRepositories()).isEqualTo(1);
            assertThat(result.repositoriesProcessed()).isZero();
        }

        @Test
        void shouldProcessEligibleAndSkipCompleteWhenAllIncrementalDone() {
            service = createService(enabledSchedulerProperties);

            SyncTarget completeRepo = createTargetWithBackfillComplete(SYNC_TARGET_ID_A, "org/repo-complete");
            SyncTarget eligibleRepo = createTargetWithIncrementalComplete(SYNC_TARGET_ID_B, "org/repo-eligible");

            SyncSession session = createSession(List.of(completeRepo, eligibleRepo));
            when(syncTargetProvider.getSyncSessions(IntegrationKind.GITHUB)).thenReturn(List.of(session));
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(5000);
            // eligibleRepo enters the per-repo loop and calls backfillRepository
            when(repositoryRepository.findByNameWithOwner("org/repo-eligible")).thenReturn(Optional.empty());

            BackfillCycleResult result = service.runBackfillCycle();

            // completeRepo: backfill complete → silently skipped (not pending, not processed)
            // eligibleRepo: attempted backfill but not in DB → backfillRepository returns false
            //   (not counted as processed, not counted as pending)
            assertThat(result.repositoriesProcessed()).isZero();
            assertThat(result.pendingRepositories()).isZero();
        }
    }

    /**
     * The per-page progress seam. The page loops that call this need a live GraphQL conversation to
     * reach, but the two rules it enforces stand on their own.
     */
    @Nested
    class NotifyPage {

        @Test
        void forwardsThePageToTheObserver() {
            List<String> seen = new ArrayList<>();

            GitHubHistoricalBackfillService.notifyPage(
                    (syncTargetId, repositoryName, phase, lowestNumberSeen, itemsSyncedInBatch) ->
                            seen.add(syncTargetId + "|"
                                    + repositoryName
                                    + "|"
                                    + phase
                                    + "|"
                                    + lowestNumberSeen
                                    + "|"
                                    + itemsSyncedInBatch),
                    7L,
                    "org/repo",
                    SyncPhase.ISSUES,
                    3200,
                    412);

            assertThat(seen).containsExactly("7|org/repo|ISSUES|3200|412");
        }

        @Test
        void unseededMinimum_isNotReportedAsProgress() {
            AtomicInteger calls = new AtomicInteger();

            // The page loops seed their running minimum at Integer.MAX_VALUE. Reporting that verbatim
            // would read as "remaining = 2147483647" and yank the bar back to zero.
            GitHubHistoricalBackfillService.notifyPage(
                    (a, b, c, d, e) -> calls.incrementAndGet(), 7L, "org/repo", SyncPhase.ISSUES, Integer.MAX_VALUE, 0);

            assertThat(calls.get()).isZero();
        }

        @Test
        void observerThrows_isSwallowedSoAProgressTickCannotFailTheBatch() {
            // A progress callback has no business aborting the batch it is only observing: losing a tick
            // is cosmetic, losing the batch means re-fetching every page in it.
            assertThatCode(() -> GitHubHistoricalBackfillService.notifyPage(
                            (a, b, c, d, e) -> {
                                throw new IllegalStateException("observer blew up");
                            },
                            7L,
                            "org/repo",
                            SyncPhase.PULL_REQUESTS,
                            100,
                            5))
                    .doesNotThrowAnyException();
        }
    }
}
