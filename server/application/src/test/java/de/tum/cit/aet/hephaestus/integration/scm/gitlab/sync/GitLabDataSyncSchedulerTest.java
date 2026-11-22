package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.activity.spi.ActivityLedgerRepair;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.RecentSyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncCursorKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.RecentWindow;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncPass;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncType;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetTestBuilder;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobHandle;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.RepositoryNotFoundOnGitProviderException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit.GitLabCommitMergeRequestLinker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabProjectResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.GitLabIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.label.GitLabLabelSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabGroupSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabSelectedMembershipProjection;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabSyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.GitLabProjectSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.subissue.GitLabSubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabAdmissionTestFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRepositoryMonitors;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceInitializationService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceLookup;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Tag("unit")
class GitLabDataSyncSchedulerTest extends BaseUnitTest {

    private final GitLabProjectAdmission projectAdmission = mock(GitLabProjectAdmission.class);

    private static final long WORKSPACE_ID = 1L;
    private static final long CONNECTION_ID = 10L;
    private static final long PROJECT_NATIVE_ID = 123L;

    @Mock
    private SyncTargetProvider syncTargetProvider;

    @Mock
    private SyncContextProvider syncContextProvider;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private ConnectionQueries connectionQueries;

    @Mock
    private SyncJobService syncJobService;

    @Mock
    private GitLabDeletionSweepService deletionSweepService;

    @Mock
    private GitLabWorkspaceInitializationService initializationService;

    @Mock
    private SyncJobHandle syncJobHandle;

    private final GitLabProjectSyncService projectSyncService = mock(GitLabProjectSyncService.class);

    // The sync services a test sets; every other one is null, so its phase no-ops and the run reaches the sweep.
    private @Nullable GitLabGroupSyncService groupSyncService;
    private @Nullable GitLabLabelSyncService labelSyncService;
    private @Nullable GitLabIssueSyncService issueSyncService;
    private @Nullable GitLabCommitMergeRequestLinker mergeRequestLinker;
    private @Nullable GitLabSubIssueSyncService subIssueSyncService;
    private @Nullable GitLabTeamSyncService teamSyncService;
    private @Nullable GitLabSelectedMembershipProjection membershipProjection;

    private GitLabDataSyncScheduler scheduler;
    private SyncSession session;

    @BeforeEach
    void setUp() {
        // The fan-out starts no workspace sync once the job service is stopping.
        lenient().when(syncJobService.isRunning()).thenReturn(true);
        SyncSchedulerProperties syncProps = new SyncSchedulerProperties(
                true,
                7,
                "0 0 3 * * *",
                15,
                new SyncSchedulerProperties.BackfillProperties(false, 50, 100, 60),
                new SyncSchedulerProperties.FilterProperties(Set.of(), Set.of()));

        // A synchronous Executor so CompletableFuture.runAsync completes inline — no thread races
        // to coordinate in assertions.
        Executor synchronousExecutor = Runnable::run;

        scheduler = new GitLabDataSyncScheduler(
                syncTargetProvider, syncProps, synchronousExecutor, connectionQueries, syncJobService, scopeSync());

        lenient().when(syncTargetProvider.isScopeActiveForSync(anyLong())).thenReturn(true);

        session = new SyncSession(
                WORKSPACE_ID,
                "my-workspace",
                "My Workspace",
                "my-group",
                null,
                "https://gitlab.com",
                List.of(),
                new SyncContextProvider.SyncContext(WORKSPACE_ID, "my-workspace", "My Workspace", null));
        lenient()
                .when(syncTargetProvider.getSyncSessions(IntegrationKind.GITLAB))
                .thenReturn(List.of(session));
        lenient()
                .when(syncTargetProvider.getSyncSession(WORKSPACE_ID, IntegrationKind.GITLAB))
                .thenReturn(Optional.of(session));
    }

    private Connection activeGitLabConnection() {
        Workspace workspace = new Workspace();
        ReflectionTestUtils.setField(workspace, "id", WORKSPACE_ID);
        Connection connection = new Connection(
                workspace,
                IntegrationKind.GITLAB,
                "gitlab.com:1",
                new ConnectionConfig.GitLabConfig("https://gitlab.com", 1L, null, Set.of(), null));
        ReflectionTestUtils.setField(connection, "id", CONNECTION_ID);
        return connection;
    }

    @Test
    void shouldLeaveTrackedFailureLoggingToTheJobBoundary() {
        var failure = new IllegalStateException("synthetic sync failure");
        when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenThrow(failure);
        // The package logger hears both beans, so a log-and-rethrow in GitLabRepositorySync fails this test too.
        Logger logger = (Logger) LoggerFactory.getLogger(GitLabScopeSync.class.getPackageName());
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasCause(failure);
            assertThat(appender.list).noneMatch(event -> event.getThrowableProxy() != null);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void shouldRecordJobWhenActiveConnectionExists() {
        Connection connection = activeGitLabConnection();
        when(connectionQueries.findActive(WORKSPACE_ID, IntegrationKind.GITLAB))
                .thenAnswer(ignored -> Optional.of(snapshotFixture(connection)));

        scheduler.syncDataCron();

        ArgumentCaptor<SyncJobRequest> requestCaptor = ArgumentCaptor.forClass(SyncJobRequest.class);
        verify(syncJobService).run(requestCaptor.capture(), any());
        SyncJobRequest request = requestCaptor.getValue();
        assertThat(request.workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(request.connectionId()).isEqualTo(CONNECTION_ID);
        assertThat(request.kind()).isEqualTo(IntegrationKind.GITLAB);
        assertThat(request.type()).isEqualTo(SyncJobType.RECONCILIATION);
        assertThat(request.trigger()).isEqualTo(SyncJobTrigger.SCHEDULED);
    }

    @Test
    void shouldRunBodyThroughJobTemplate() {
        Connection connection = activeGitLabConnection();
        when(connectionQueries.findActive(WORKSPACE_ID, IntegrationKind.GITLAB))
                .thenAnswer(ignored -> Optional.of(snapshotFixture(connection)));
        doAnswer(inv -> {
                    Consumer<SyncJobHandle> body = inv.getArgument(1);
                    body.accept(syncJobHandle);
                    return null;
                })
                .when(syncJobService)
                .run(any(), any());
        when(deletionSweepService.sweepScope(WORKSPACE_ID, syncJobHandle))
                .thenReturn(new GitLabDeletionSweepService.SweepOutcome(0, 0, 0, false, 0));

        scheduler.syncDataCron();

        verify(deletionSweepService).sweepScope(WORKSPACE_ID, syncJobHandle);
        verify(syncContextProvider).setContext(any());
        verify(syncContextProvider).clearContext();
    }

    @Test
    void shouldRunUnrecordedWhenNoActiveConnection() {
        when(connectionQueries.findActive(eq(WORKSPACE_ID), eq(IntegrationKind.GITLAB)))
                .thenReturn(Optional.empty());
        when(deletionSweepService.sweepScope(eq(WORKSPACE_ID), isNull()))
                .thenReturn(new GitLabDeletionSweepService.SweepOutcome(0, 0, 0, false, 0));

        scheduler.syncDataCron();

        verify(syncJobService, never()).run(any(), any());
        verify(deletionSweepService).sweepScope(eq(WORKSPACE_ID), isNull());
    }

    @Test
    void shouldSkipScopeOnJobConflictWithoutFailingCron() {
        Connection connection = activeGitLabConnection();
        when(connectionQueries.findActive(WORKSPACE_ID, IntegrationKind.GITLAB))
                .thenAnswer(ignored -> Optional.of(snapshotFixture(connection)));
        doThrow(new SyncJobConflictException(existingActiveJob(connection)))
                .when(syncJobService)
                .run(any(), any());

        assertThatCode(() -> scheduler.syncDataCron()).doesNotThrowAnyException();

        verify(syncContextProvider, never()).setContext(any());
    }

    @Test
    void shouldRunDeletionSweepLastOnReconciliation() {
        // With every sync service absent, the scope sync runs to completion and ends with the sweep. A
        // RECONCILIATION job must invoke it — this is the whole point of the type on GitLab.
        lenient()
                .when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID))
                .thenReturn(List.of());
        when(deletionSweepService.sweepScope(eq(WORKSPACE_ID), any()))
                .thenReturn(new GitLabDeletionSweepService.SweepOutcome(0, 0, 0, false, 0));

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.RECONCILIATION);

        verify(deletionSweepService).sweepScope(eq(WORKSPACE_ID), eq(syncJobHandle));
    }

    @Test
    void shouldNotRunDeletionSweepOnInitial() {
        // INITIAL populates a fresh mirror; there is nothing stale in it and every not-yet-fetched row
        // would look deleted. It must skip the sweep entirely — otherwise a manual INITIAL on a mature
        // connection would mass-tombstone.
        lenient()
                .when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID))
                .thenReturn(List.of());

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);

        verify(deletionSweepService, never()).sweepScope(any(), any());
    }

    @Test
    void shouldReportWarningsWhenSweepCouldNotVerifyEveryProject() {
        lenient()
                .when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID))
                .thenReturn(List.of());
        when(deletionSweepService.sweepScope(eq(WORKSPACE_ID), any()))
                .thenReturn(new GitLabDeletionSweepService.SweepOutcome(0, 0, 0, true, 0));

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.RECONCILIATION);

        verify(syncJobHandle).reportWarnings();
    }

    @Test
    void shouldNotRunDeletionSweepWhenCancelledDuringRepositoryPhase() {
        // A cancel observed during the repo phase skips the remaining phases, the sweep included.
        lenient()
                .when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID))
                .thenReturn(List.of());
        when(syncJobHandle.isCancellationRequested()).thenReturn(true);

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.RECONCILIATION);

        verify(deletionSweepService, never()).sweepScope(any(), any());
    }

    @Test
    void shouldProjectSelectedMembersAfterTheTeamsAndWarnWhenNothingChanged() {
        var teamSync = mock(GitLabTeamSyncService.class);
        var projection = mock(GitLabSelectedMembershipProjection.class);
        teamSyncService = teamSync;
        membershipProjection = projection;
        lenient()
                .when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID))
                .thenReturn(List.of());
        when(teamSync.syncTeamsForGroup(eq(WORKSPACE_ID), any())).thenReturn(new GitLabTeamSyncService.Result(0, true));
        when(projection.project(WORKSPACE_ID)).thenReturn(GitLabSelectedMembershipProjection.Outcome.STALE);

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);

        InOrder order = inOrder(teamSync, projection);
        order.verify(teamSync).syncTeamsForGroup(eq(WORKSPACE_ID), any());
        order.verify(projection).project(WORKSPACE_ID);
        verify(syncJobHandle).reportWarnings();
    }

    @Test
    void shouldNotProjectSelectedMembersWhenCancelledDuringRepositoryPhase() {
        var projection = mock(GitLabSelectedMembershipProjection.class);
        membershipProjection = projection;
        lenient()
                .when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID))
                .thenReturn(List.of());
        when(syncJobHandle.isCancellationRequested()).thenReturn(true);

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.RECONCILIATION);

        verify(projection, never()).project(anyLong());
    }

    @Test
    void shouldNotProjectSelectedMembersWhenCancelledDuringTeamPhase() {
        var teamSync = mock(GitLabTeamSyncService.class);
        var projection = mock(GitLabSelectedMembershipProjection.class);
        teamSyncService = teamSync;
        membershipProjection = projection;
        lenient()
                .when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID))
                .thenReturn(List.of());
        var cancelled = new AtomicBoolean();
        when(syncJobHandle.isCancellationRequested()).thenAnswer(invocation -> cancelled.get());
        when(teamSync.syncTeamsForGroup(eq(WORKSPACE_ID), any())).thenAnswer(invocation -> {
            cancelled.set(true);
            return new GitLabTeamSyncService.Result(0, true);
        });

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);

        verify(projection, never()).project(anyLong());
    }

    @Test
    void shouldCallNoProjectServiceOnceTheProviderRefusedTheCredential() {
        var issueSync = mock(GitLabIssueSyncService.class);
        var subIssueSync = mock(GitLabSubIssueSyncService.class);
        issueSyncService = issueSync;
        subIssueSyncService = subIssueSync;
        prepareProject();
        // The first refused call of this run closed the gate before the repository phase.
        when(syncTargetProvider.isScopeActiveForSync(WORKSPACE_ID)).thenReturn(false);

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.RECONCILIATION);

        verifyNoInteractions(issueSync, subIssueSync, deletionSweepService);
        verify(syncJobHandle).reportWarnings();
    }

    @Test
    void shouldHoldTheWatermarkWhenIssuesStopAtThePageCapAndReadTheRestInTheNextRun() {
        var issueSync = mock(GitLabIssueSyncService.class);
        issueSyncService = issueSync;
        Repository project = prepareProject();
        when(issueSync.syncIssues(eq(WORKSPACE_ID), eq(project), any(), isNull(), any()))
                .thenReturn(new RecentSyncResult(SyncResult.abortedError(30_000), "cursor-at-cap"));

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);

        verify(repositoryRepository, never()).updateLastSyncAt(eq(99L), any());
        var opened = ArgumentCaptor.forClass(RecentWindow.class);
        verify(syncTargetProvider).updateRecentWindow(eq(77L), eq(SyncCursorKind.ISSUE), opened.capture());
        assertThat(opened.getValue().resumeCursor()).isEqualTo("cursor-at-cap");
        Instant windowStart = opened.getValue().startedAt();

        when(syncTargetProvider.findSyncTargetById(77L))
                .thenReturn(Optional.of(SyncTargetTestBuilder.syncTarget()
                        .id(77L)
                        .issueRecentWindow(opened.getValue())
                        .build()));
        when(issueSync.syncIssues(eq(WORKSPACE_ID), eq(project), any(), eq("cursor-at-cap"), any()))
                .thenReturn(new RecentSyncResult(SyncResult.completed(4_000), null));

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);

        verify(syncTargetProvider).updateRecentWindow(77L, SyncCursorKind.ISSUE, null);
        verify(repositoryRepository).updateLastSyncAt(99L, windowStart);
    }

    @Test
    void shouldRecordIncompleteProjectSyncAndClearAfterRecovery() {
        var issueSync = mock(GitLabIssueSyncService.class);
        issueSyncService = issueSync;
        Repository project = prepareProject();
        when(issueSync.syncIssues(eq(WORKSPACE_ID), eq(project), any(), any(), any()))
                .thenReturn(
                        new RecentSyncResult(SyncResult.abortedError(1), null),
                        new RecentSyncResult(SyncResult.completed(2), null));

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);
        verify(syncTargetProvider).updateSyncError(77L, SyncPass.RECENT, "Issue sync: ABORTED_ERROR");

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);
        verify(syncTargetProvider).updateSyncError(77L, SyncPass.RECENT, null);
    }

    @Test
    void shouldNotClearAnIssueErrorWhenTheLaterSubIssuePhaseSucceeds() {
        var issueSync = mock(GitLabIssueSyncService.class);
        var subIssueSync = mock(GitLabSubIssueSyncService.class);
        issueSyncService = issueSync;
        subIssueSyncService = subIssueSync;
        Repository project = prepareProject();
        when(issueSync.syncIssues(eq(WORKSPACE_ID), eq(project), any(), any(), any()))
                .thenReturn(new RecentSyncResult(SyncResult.abortedError(1), null));
        when(subIssueSync.syncSubIssuesForRepository(eq(WORKSPACE_ID), eq(project), any()))
                .thenReturn(SyncResult.completed(1));

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);

        verify(syncTargetProvider).updateSyncError(77L, SyncPass.RECENT, "Issue sync: ABORTED_ERROR");
    }

    @Test
    void shouldClearAProjectErrorAfterTheFailedPostPhaseRecovers() {
        var subIssueSync = mock(GitLabSubIssueSyncService.class);
        subIssueSyncService = subIssueSync;
        Repository project = prepareProject();
        when(subIssueSync.syncSubIssuesForRepository(eq(WORKSPACE_ID), eq(project), any()))
                .thenReturn(SyncResult.abortedError(0), SyncResult.completed(1));

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);
        verify(syncTargetProvider).updateSyncError(77L, SyncPass.RECENT, "Sub-issue sync: ABORTED_ERROR");

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);
        verify(syncTargetProvider).updateSyncError(77L, SyncPass.RECENT, null);
    }

    @Test
    void shouldNotAdvanceRepositoryCooldownAfterAuthorityIsWithdrawnAtTheIssueWatermark() {
        var issueSync = mock(GitLabIssueSyncService.class);
        issueSyncService = issueSync;
        Repository stopped = prepareProject();
        var originalRead = GitLabAdmissionTestFixtures.permit(projectAdmission, WORKSPACE_ID, stopped, 77L);
        Repository sibling = new Repository();
        sibling.setId(100L);
        sibling.setNativeId(124L);
        sibling.setNameWithOwner("course/sibling");
        sibling.setProvider(stopped.getProvider());
        GitLabAdmissionTestFixtures.permit(projectAdmission, WORKSPACE_ID, sibling, 78L);
        var siblingTarget = SyncTargetTestBuilder.syncTarget()
                .id(78L)
                .nativeId(sibling.getNativeId())
                .scopeId(WORKSPACE_ID)
                .repositoryNameWithOwner(sibling.getNameWithOwner())
                .build();
        when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenReturn(List.of(stopped, sibling));
        when(syncTargetProvider.getSyncTargetsForScope(WORKSPACE_ID))
                .thenReturn(List.of(session.syncTargets().getFirst(), siblingTarget));
        var siblingMetadata = mock(GitLabProjectResponse.class);
        when(projectSyncService.fetchProjectById(WORKSPACE_ID, sibling.getNativeId()))
                .thenReturn(Optional.of(siblingMetadata));
        when(projectSyncService.persistProject(siblingMetadata, sibling.getProvider()))
                .thenReturn(Optional.of(sibling));
        when(issueSync.syncIssues(eq(WORKSPACE_ID), any(), any(), any(), any()))
                .thenReturn(new RecentSyncResult(SyncResult.completed(1), null));
        lenient()
                .doAnswer(invocation -> {
                    doReturn(false).when(projectAdmission).hold(originalRead);
                    return null;
                })
                .when(syncTargetProvider)
                .updateSyncTimestamp(eq(77L), eq(SyncType.ISSUES), any());

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);

        verify(repositoryRepository).updateLastSyncAt(eq(100L), any());
        verify(syncTargetProvider).updateSyncTimestamp(eq(78L), eq(SyncType.FULL_REPOSITORY), any());
        verify(repositoryRepository, never()).updateLastSyncAt(eq(99L), any());
        verify(syncTargetProvider, never()).updateSyncTimestamp(eq(77L), eq(SyncType.FULL_REPOSITORY), any());
        verify(syncJobHandle, never()).reportWarnings();
    }

    @Test
    void shouldNotMarkAProjectFreshWhenNoSyncServiceRuns() {
        prepareProject();

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);

        verify(repositoryRepository, never()).updateLastSyncAt(eq(99L), any());
        verify(syncTargetProvider, never()).updateSyncError(eq(77L), eq(SyncPass.RECENT), isNull());
    }

    @Test
    void shouldNotAdvanceLabelWatermarkWhenProjectLabelSyncAborts() {
        var labelSync = mock(GitLabLabelSyncService.class);
        labelSyncService = labelSync;
        Repository project = prepareProject();
        when(labelSync.syncLabelsForRepository(eq(WORKSPACE_ID), eq(project), any()))
                .thenReturn(SyncResult.abortedError(0));

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);

        verify(syncTargetProvider).updateSyncError(77L, SyncPass.RECENT, "Label sync: ABORTED_ERROR");
        verify(syncTargetProvider, never()).updateSyncTimestamp(eq(77L), eq(SyncType.LABELS), any());
    }

    @Test
    void shouldPreserveWorkAndMonitorWhenProjectIsNoLongerAccessible() {
        prepareProject();
        var groupSync = mock(GitLabGroupSyncService.class);
        groupSyncService = groupSync;
        when(groupSync.syncGroupProjects(WORKSPACE_ID, "my-group", "https://gitlab.com"))
                .thenReturn(GitLabSyncResult.completed(List.of(), 1, 0, 0));
        when(projectSyncService.fetchProjectById(WORKSPACE_ID, PROJECT_NATIVE_ID))
                .thenReturn(Optional.empty());
        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);
        verify(syncTargetProvider).recordRepositoryUnavailable(WORKSPACE_ID, 77L);
        verify(repositoryRepository, never()).delete(any());
        verify(syncTargetProvider, never()).removeSyncTarget(any());
        verify(syncTargetProvider, never()).clearRepositoryUnavailable(any(), any());
    }

    @Test
    void shouldStopPublicationWhenMetadataFetchReportsAccessLoss() {
        prepareProject();
        when(projectSyncService.fetchProjectById(WORKSPACE_ID, PROJECT_NATIVE_ID))
                .thenThrow(new RepositoryNotFoundOnGitProviderException("course/project"));
        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);
        verify(syncTargetProvider).recordRepositoryUnavailable(WORKSPACE_ID, 77L);
        verify(syncTargetProvider, never()).updateSyncTimestamp(eq(77L), eq(SyncType.FULL_REPOSITORY), any());
        verify(syncTargetProvider, never()).updateSyncTimestamp(eq(77L), eq(SyncType.REPOSITORY_VISIBILITY), any());
    }

    @Test
    void shouldSkipAllProjectRequestsAfterMetadataFailure() {
        prepareProject();
        var linker = mock(GitLabCommitMergeRequestLinker.class);
        var subIssues = mock(GitLabSubIssueSyncService.class);
        mergeRequestLinker = linker;
        subIssueSyncService = subIssues;
        when(projectSyncService.fetchProjectById(WORKSPACE_ID, PROJECT_NATIVE_ID))
                .thenReturn(Optional.empty());

        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);

        verify(linker, never()).linkCommits(any(), any(), any(), any());
        verify(subIssues, never()).syncSubIssuesForRepository(any(), any(), any());
        verify(syncTargetProvider).recordRepositoryUnavailable(WORKSPACE_ID, 77L);
    }

    @Test
    void shouldSkipMetadataAndCommitFetchWhenRecheckIsNotDue() {
        prepareProject();
        when(syncTargetProvider.deferUnavailableRepository(WORKSPACE_ID, 77L)).thenReturn(true);
        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);
        verify(projectSyncService, never()).fetchProjectById(anyLong(), anyLong());
        verify(syncTargetProvider, never()).clearRepositoryUnavailable(any(), any());
    }

    @Test
    void shouldRecoverWhenProjectReappears() {
        var project = prepareProject();
        var linker = mock(GitLabCommitMergeRequestLinker.class);
        var subIssues = mock(GitLabSubIssueSyncService.class);
        mergeRequestLinker = linker;
        subIssueSyncService = subIssues;
        when(linker.linkCommits(eq(WORKSPACE_ID), eq(project), eq(null), any())).thenReturn(SyncResult.completed(1));
        when(subIssues.syncSubIssuesForRepository(eq(WORKSPACE_ID), eq(project), any()))
                .thenReturn(SyncResult.completed(1));
        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);
        verify(syncTargetProvider).clearRepositoryUnavailable(WORKSPACE_ID, 77L);
        verify(syncTargetProvider).updateSyncTimestamp(eq(77L), eq(SyncType.REPOSITORY_VISIBILITY), any());
        verify(linker).linkCommits(eq(WORKSPACE_ID), eq(project), eq(null), any());
        verify(subIssues).syncSubIssuesForRepository(eq(WORKSPACE_ID), eq(project), any());
    }

    @Test
    void shouldNotStartRepositoryBackoffForTransientMetadataFailures() {
        prepareProject();
        when(projectSyncService.fetchProjectById(WORKSPACE_ID, PROJECT_NATIVE_ID))
                .thenThrow(WebClientResponseException.create(
                        503, "Service Unavailable", HttpHeaders.EMPTY, new byte[0], null))
                .thenThrow(WebClientResponseException.create(
                        429, "Too Many Requests", HttpHeaders.EMPTY, new byte[0], null));
        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);
        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);
        verify(syncTargetProvider, never()).recordRepositoryUnavailable(any(), any());
        verify(syncTargetProvider, times(2)).retryUnavailableRepository(WORKSPACE_ID, 77L);
        verify(syncTargetProvider, never()).updateSyncTimestamp(eq(77L), eq(SyncType.REPOSITORY_VISIBILITY), any());
    }

    @Test
    void shouldResolveRenamedProjectByStableId() {
        var project = prepareProject();
        project.setNativeId(123L);
        var metadata = mock(GitLabProjectResponse.class);
        when(projectSyncService.fetchProjectById(WORKSPACE_ID, 123L)).thenReturn(Optional.of(metadata));
        when(projectSyncService.persistProject(metadata, project.getProvider())).thenAnswer(invocation -> {
            project.setNameWithOwner("course/renamed");
            return Optional.of(project);
        });
        scopeSync().syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);
        verify(syncTargetProvider).reconcileSyncTargetIdentity(77L, 123L, "course/renamed");
        verify(syncTargetProvider).clearRepositoryUnavailable(WORKSPACE_ID, 77L);
        verify(syncTargetProvider).updateSyncTimestamp(eq(77L), eq(SyncType.REPOSITORY_VISIBILITY), any());
        verify(repositoryRepository, never()).delete(any());
    }

    private Repository prepareProject() {
        Repository project = new Repository();
        project.setId(99L);
        project.setNativeId(PROJECT_NATIVE_ID);
        project.setNameWithOwner("course/project");
        GitLabAdmissionTestFixtures.permit(projectAdmission, WORKSPACE_ID, project, 77L);
        when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenReturn(List.of(project));
        var target = SyncTargetTestBuilder.syncTarget()
                .id(77L)
                .nativeId(PROJECT_NATIVE_ID)
                .scopeId(WORKSPACE_ID)
                .repositoryNameWithOwner("course/project")
                .build();
        session = new SyncSession(
                WORKSPACE_ID,
                "my-workspace",
                "My Workspace",
                "my-group",
                null,
                "https://gitlab.com",
                List.of(target),
                session.syncContext());
        when(syncTargetProvider.getSyncSession(WORKSPACE_ID, IntegrationKind.GITLAB))
                .thenReturn(Optional.of(session));
        when(syncTargetProvider.getSyncTargetsForScope(WORKSPACE_ID)).thenReturn(List.of(target));
        project.setProvider(TestEntities.gitProvider(100L, IdentityProviderType.GITLAB));
        var metadata = mock(GitLabProjectResponse.class);
        lenient()
                .when(projectSyncService.fetchProjectById(WORKSPACE_ID, PROJECT_NATIVE_ID))
                .thenReturn(Optional.of(metadata));
        lenient()
                .when(projectSyncService.persistProject(metadata, project.getProvider()))
                .thenReturn(Optional.of(project));
        return project;
    }

    /** A scope sync over the sync services this test set; the rate-limit tracker is absent. */
    private GitLabScopeSync scopeSync() {
        var repositorySync = new GitLabRepositorySync(
                syncTargetProvider,
                repositoryRepository,
                null,
                mock(WorkspaceActorSelector.class),
                projectSyncService,
                mock(ActivityLedgerRepair.class),
                projectAdmission,
                labelSyncService,
                null,
                issueSyncService,
                null,
                null,
                null,
                null,
                mergeRequestLinker,
                subIssueSyncService,
                null);
        return new GitLabScopeSync(
                syncTargetProvider,
                syncContextProvider,
                deletionSweepService,
                initializationService,
                mock(GitLabRepositoryMonitors.class),
                mock(WorkspaceLookup.class),
                repositorySync,
                groupSyncService,
                null,
                null,
                teamSyncService,
                membershipProjection);
    }

    private SyncJob existingActiveJob(Connection connection) {
        SyncJob job = new SyncJob(
                connection.getWorkspace(),
                connection,
                IntegrationKind.GITLAB,
                SyncJobType.RECONCILIATION,
                SyncJobTrigger.SCHEDULED,
                null);
        ReflectionTestUtils.setField(job, "id", 500L);
        return job;
    }

    private static ConnectionQueries.ConnectionSnapshot snapshotFixture(Connection connection) {
        return new ConnectionQueries.ConnectionSnapshot(
                connection.getId(), connection.getVersion(), connection.getConfig());
    }
}
