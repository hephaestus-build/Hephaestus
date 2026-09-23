package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
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
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncServiceHolder;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.GitLabIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.label.GitLabLabelSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.subissue.GitLabSubIssueSyncService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class GitlabDataSyncSchedulerTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long CONNECTION_ID = 10L;

    @Mock
    private SyncTargetProvider syncTargetProvider;

    @Mock
    private SyncContextProvider syncContextProvider;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private ObjectProvider<GitLabSyncServiceHolder> syncServiceHolderProvider;

    @Mock
    private ObjectProvider<GitLabRateLimitTracker> rateLimitTrackerProvider;

    @Mock
    private ConnectionRepository connectionRepository;

    @Mock
    private SyncJobService syncJobService;

    @Mock
    private GitLabDeletionSweepService deletionSweepService;

    @Mock
    private SyncJobHandle syncJobHandle;

    private GitlabDataSyncScheduler scheduler;
    private SyncSession session;

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

        // A synchronous Executor so CompletableFuture.runAsync completes inline — no thread races
        // to coordinate in assertions.
        Executor synchronousExecutor = Runnable::run;

        scheduler = new GitlabDataSyncScheduler(
                syncTargetProvider,
                syncContextProvider,
                organizationRepository,
                repositoryRepository,
                syncServiceHolderProvider,
                rateLimitTrackerProvider,
                syncProps,
                synchronousExecutor,
                connectionRepository,
                syncJobService,
                deletionSweepService);

        // syncScope's first real step: no GitLabSyncServiceHolder available -> it logs and returns
        // immediately. This isolates the job-recording wrapper from the sync pipeline itself.
        lenient().when(syncServiceHolderProvider.getIfAvailable()).thenReturn(null);

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
    }

    private Connection activeGitLabConnection() {
        Workspace workspace = new Workspace();
        ReflectionTestUtils.setField(workspace, "id", WORKSPACE_ID);
        Connection connection = new Connection(
                workspace,
                IntegrationKind.GITLAB,
                "gitlab.com:1",
                new ConnectionConfig.GitLabConfig(
                        "https://gitlab.com", 1L, null, ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT, Set.of()));
        ReflectionTestUtils.setField(connection, "id", CONNECTION_ID);
        return connection;
    }

    @Test
    void shouldRecordJobWhenActiveConnectionExists() {
        Connection connection = activeGitLabConnection();
        when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        WORKSPACE_ID, IntegrationKind.GITLAB, IntegrationState.ACTIVE))
                .thenReturn(Optional.of(connection));

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
        when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        WORKSPACE_ID, IntegrationKind.GITLAB, IntegrationState.ACTIVE))
                .thenReturn(Optional.of(connection));
        doAnswer(inv -> {
                    Consumer<SyncJobHandle> body = inv.getArgument(1);
                    body.accept(syncJobHandle);
                    return null;
                })
                .when(syncJobService)
                .run(any(), any());

        scheduler.syncDataCron();

        verify(syncServiceHolderProvider).getIfAvailable();
        verify(syncContextProvider).setContext(any());
        verify(syncContextProvider).clearContext();
    }

    @Test
    void shouldRunUnrecordedWhenNoActiveConnection() {
        when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        eq(WORKSPACE_ID), eq(IntegrationKind.GITLAB), eq(IntegrationState.ACTIVE)))
                .thenReturn(Optional.empty());

        scheduler.syncDataCron();

        verify(syncJobService, never()).run(any(), any());
        verify(syncServiceHolderProvider).getIfAvailable();
    }

    @Test
    void shouldSkipScopeOnJobConflictWithoutFailingCron() {
        Connection connection = activeGitLabConnection();
        when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        WORKSPACE_ID, IntegrationKind.GITLAB, IntegrationState.ACTIVE))
                .thenReturn(Optional.of(connection));
        doThrow(new SyncJobConflictException(existingActiveJob(connection)))
                .when(syncJobService)
                .run(any(), any());

        assertThatCode(() -> scheduler.syncDataCron()).doesNotThrowAnyException();

        verify(syncServiceHolderProvider, never()).getIfAvailable();
    }

    @Test
    void shouldRunDeletionSweepLastOnReconciliation() {
        // With the service holder present, syncScope runs to completion and ends with the sweep. A
        // RECONCILIATION job must invoke it — this is the whole point of the type on GitLab.
        when(syncServiceHolderProvider.getIfAvailable()).thenReturn(mockHolder());
        lenient()
                .when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID))
                .thenReturn(List.of());
        when(deletionSweepService.sweepScope(eq(WORKSPACE_ID), any()))
                .thenReturn(new GitLabDeletionSweepService.SweepOutcome(0, 0, false));

        scheduler.syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.RECONCILIATION);

        verify(deletionSweepService).sweepScope(eq(WORKSPACE_ID), eq(syncJobHandle));
    }

    @Test
    void shouldNotRunDeletionSweepOnInitial() {
        // INITIAL populates a fresh mirror; there is nothing stale in it and every not-yet-fetched row
        // would look deleted. It must skip the sweep entirely — otherwise a manual INITIAL on a mature
        // connection would mass-tombstone.
        when(syncServiceHolderProvider.getIfAvailable()).thenReturn(mockHolder());
        lenient()
                .when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID))
                .thenReturn(List.of());

        scheduler.syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);

        verify(deletionSweepService, never()).sweepScope(any(), any());
    }

    @Test
    void shouldReportWarningsWhenSweepCouldNotVerifyEveryProject() {
        when(syncServiceHolderProvider.getIfAvailable()).thenReturn(mockHolder());
        lenient()
                .when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID))
                .thenReturn(List.of());
        when(deletionSweepService.sweepScope(eq(WORKSPACE_ID), any()))
                .thenReturn(new GitLabDeletionSweepService.SweepOutcome(0, 0, true));

        scheduler.syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.RECONCILIATION);

        verify(syncJobHandle).reportWarnings();
    }

    @Test
    void shouldNotRunDeletionSweepWhenCancelledDuringRepositoryPhase() {
        // A cancel observed during the repo phase skips the remaining phases, the sweep included.
        when(syncServiceHolderProvider.getIfAvailable()).thenReturn(mockHolder());
        lenient()
                .when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID))
                .thenReturn(List.of());
        when(syncJobHandle.isCancellationRequested()).thenReturn(true);

        scheduler.syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.RECONCILIATION);

        verify(deletionSweepService, never()).sweepScope(any(), any());
    }

    @Test
    void shouldRecordIncompleteProjectSyncAndClearAfterRecovery() {
        var holder = mockHolder();
        var issueSync = org.mockito.Mockito.mock(GitLabIssueSyncService.class);
        when(holder.getIssueSyncService()).thenReturn(issueSync);
        Repository project = prepareProject(holder);
        when(issueSync.syncIssues(eq(WORKSPACE_ID), eq(project), any()))
                .thenReturn(SyncResult.abortedError(1), SyncResult.completed(2));

        scheduler.syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);
        verify(syncTargetProvider).updateSyncError(77L, SyncPass.RECENT, "Issue sync: ABORTED_ERROR");

        scheduler.syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);
        verify(syncTargetProvider).updateSyncError(77L, SyncPass.RECENT, null);
    }

    @Test
    void shouldNotClearAnIssueErrorWhenTheLaterSubIssuePhaseSucceeds() {
        var holder = mockHolder();
        var issueSync = org.mockito.Mockito.mock(GitLabIssueSyncService.class);
        var subIssueSync = org.mockito.Mockito.mock(GitLabSubIssueSyncService.class);
        when(holder.getIssueSyncService()).thenReturn(issueSync);
        when(holder.getSubIssueSyncService()).thenReturn(subIssueSync);
        Repository project = prepareProject(holder);
        when(issueSync.syncIssues(eq(WORKSPACE_ID), eq(project), any())).thenReturn(SyncResult.abortedError(1));
        when(subIssueSync.syncSubIssuesForRepository(WORKSPACE_ID, project)).thenReturn(SyncResult.completed(1));

        scheduler.syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);

        verify(syncTargetProvider).updateSyncError(77L, SyncPass.RECENT, "Issue sync: ABORTED_ERROR");
    }

    @Test
    void shouldClearAProjectErrorAfterTheFailedPostPhaseRecovers() {
        var holder = mockHolder();
        var subIssueSync = org.mockito.Mockito.mock(GitLabSubIssueSyncService.class);
        when(holder.getSubIssueSyncService()).thenReturn(subIssueSync);
        Repository project = prepareProject(holder);
        when(subIssueSync.syncSubIssuesForRepository(WORKSPACE_ID, project))
                .thenReturn(SyncResult.abortedError(0), SyncResult.completed(1));

        scheduler.syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);
        verify(syncTargetProvider).updateSyncError(77L, SyncPass.RECENT, "Sub-issue sync: ABORTED_ERROR");

        scheduler.syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);
        verify(syncTargetProvider).updateSyncError(77L, SyncPass.RECENT, null);
    }

    @Test
    void shouldNotMarkAProjectFreshWhenNoSyncServiceRuns() {
        prepareProject(mockHolder());

        scheduler.syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);

        verify(repositoryRepository, never()).updateLastSyncAt(eq(99L), any());
        verify(syncTargetProvider, never()).updateSyncError(eq(77L), eq(SyncPass.RECENT), isNull());
    }

    @Test
    void shouldNotAdvanceLabelWatermarkWhenProjectLabelSyncAborts() {
        var holder = mockHolder();
        var labelSync = org.mockito.Mockito.mock(GitLabLabelSyncService.class);
        when(holder.getLabelSyncService()).thenReturn(labelSync);
        Repository project = prepareProject(holder);
        when(labelSync.syncLabelsForRepository(WORKSPACE_ID, project)).thenReturn(SyncResult.abortedError(0));

        scheduler.syncWorkspaceNow(WORKSPACE_ID, syncJobHandle, SyncJobType.INITIAL);

        verify(syncTargetProvider).updateSyncError(77L, SyncPass.RECENT, "Label sync: ABORTED_ERROR");
        verify(syncTargetProvider, never()).updateSyncTimestamp(eq(77L), eq(SyncType.LABELS), any());
    }

    private Repository prepareProject(GitLabSyncServiceHolder holder) {
        when(syncServiceHolderProvider.getIfAvailable()).thenReturn(holder);
        Repository project = new Repository();
        project.setId(99L);
        project.setNameWithOwner("course/project");
        when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenReturn(List.of(project));
        var target = SyncTargetTestBuilder.syncTarget()
                .id(77L)
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
        when(syncTargetProvider.getSyncSessions(IntegrationKind.GITLAB)).thenReturn(List.of(session));
        return project;
    }

    /** A holder whose every sub-service is null, so all sync phases no-op and the run reaches the sweep. */
    private static GitLabSyncServiceHolder mockHolder() {
        return org.mockito.Mockito.mock(GitLabSyncServiceHolder.class);
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
}
