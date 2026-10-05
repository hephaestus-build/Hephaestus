package de.tum.cit.aet.hephaestus.integration.scm.github.sync;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
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
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.BackfillProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.DiscussionsProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.FilterProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.ProjectsProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider.SyncContext;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncStatistics;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.RateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuedependency.GitHubIssueDependencySyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuetype.GitHubIssueTypeSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.organization.GitHubOrganizationSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.GitHubProjectSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.subissue.GitHubSubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.team.GitHubTeamSyncService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

class GitHubDataSyncSchedulerTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long CONNECTION_ID = 10L;

    @Mock
    private SyncTargetProvider syncTargetProvider;

    @Mock
    private SyncContextProvider syncContextProvider;

    @Mock
    private GitHubDataSyncService dataSyncService;

    @Mock
    private GitHubDeletionSweepService deletionSweepService;

    @Mock
    private GitHubSubIssueSyncService subIssueSyncService;

    @Mock
    private GitHubIssueTypeSyncService issueTypeSyncService;

    @Mock
    private GitHubIssueDependencySyncService issueDependencySyncService;

    @Mock
    private GitHubProjectSyncService projectSyncService;

    @Mock
    private GitHubOrganizationSyncService organizationSyncService;

    @Mock
    private GitHubTeamSyncService teamSyncService;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private RateLimitTracker rateLimitTracker;

    @Mock
    private ConnectionRepository connectionRepository;

    @Mock
    private SyncJobService syncJobService;

    private GitHubDataSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        SyncSchedulerProperties properties = new SyncSchedulerProperties(
                true,
                7,
                "0 0 3 * * *",
                15,
                new BackfillProperties(false, 50, 100, 60),
                new FilterProperties(Set.of(), Set.of(), Set.of()),
                new DiscussionsProperties(false),
                new ProjectsProperties(false));

        scheduler = new GitHubDataSyncScheduler(
                syncTargetProvider,
                syncContextProvider,
                dataSyncService,
                deletionSweepService,
                subIssueSyncService,
                issueTypeSyncService,
                issueDependencySyncService,
                projectSyncService,
                organizationSyncService,
                teamSyncService,
                organizationRepository,
                properties,
                rateLimitTracker,
                Runnable::run, // synchronous executor — deterministic assertions, no timing flakiness
                connectionRepository,
                syncJobService);

        lenient().when(syncTargetProvider.getSyncStatistics()).thenReturn(new SyncStatistics(1, 0, 0, 1, false));
        // Skip the sub-issue/dependency gate — irrelevant to job-recording behavior, avoids stubbing
        // subIssueSyncService/issueDependencySyncService for every test.
        lenient().when(rateLimitTracker.isCritical(WORKSPACE_ID)).thenReturn(true);
        lenient().when(syncContextProvider.wrapWithContext(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static SyncSession session() {
        // Blank accountLogin -> the scheduler's syncTeams short-circuits without needing
        // teamSyncService/organizationRepository stubs.
        return new SyncSession(
                WORKSPACE_ID,
                "acme-workspace",
                "Acme",
                "",
                100L,
                null,
                List.of(),
                new SyncContext(WORKSPACE_ID, "acme-workspace", "Acme", 100L));
    }

    private static Connection activeGithubConnection() {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        Connection connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "100",
                new ConnectionConfig.GitHubAppConfig(100L, "acme", null, Set.of()));
        connection.setState(IntegrationState.ACTIVE);
        ReflectionTestUtils.setField(connection, "id", CONNECTION_ID);
        return connection;
    }

    @Nested
    class RecordsJob {

        @Test
        void activeConnectionPresent_recordsReconciliationScheduledJobAndRunsBody() {
            when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                            WORKSPACE_ID, IntegrationKind.GITHUB, IntegrationState.ACTIVE))
                    .thenReturn(Optional.of(activeGithubConnection()));
            when(syncTargetProvider.getSyncSessions(IntegrationKind.GITHUB)).thenReturn(List.of(session()));
            doAnswer(invocation -> {
                        Consumer<Object> body = invocation.getArgument(1);
                        body.accept(null); // no assertions in the runner body need a real SyncJobHandle
                        return null;
                    })
                    .when(syncJobService)
                    .run(any(), any());

            scheduler.syncDataCron();

            verify(syncJobService)
                    .run(
                            argThat((SyncJobRequest req) -> req.workspaceId() == WORKSPACE_ID
                                    && req.connectionId() == CONNECTION_ID
                                    && req.kind() == IntegrationKind.GITHUB
                                    && req.type() == SyncJobType.RECONCILIATION
                                    && req.trigger() == SyncJobTrigger.SCHEDULED),
                            any());
            verify(issueTypeSyncService).syncIssueTypesForScope(WORKSPACE_ID);
        }
    }

    @Nested
    class SkipsOnConflict {

        @Test
        void activeSyncJobAlreadyRunning_skipsScopeWithoutRunningBodyOrThrowing() {
            when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                            WORKSPACE_ID, IntegrationKind.GITHUB, IntegrationState.ACTIVE))
                    .thenReturn(Optional.of(activeGithubConnection()));
            when(syncTargetProvider.getSyncSessions(IntegrationKind.GITHUB)).thenReturn(List.of(session()));

            SyncJob activeJob = new SyncJob(
                    new Workspace(),
                    activeGithubConnection(),
                    IntegrationKind.GITHUB,
                    SyncJobType.RECONCILIATION,
                    SyncJobTrigger.MANUAL,
                    null);
            activeJob.setId(77L);
            doThrow(new SyncJobConflictException(activeJob))
                    .when(syncJobService)
                    .run(any(), any());

            assertThatCode(() -> scheduler.syncDataCron()).doesNotThrowAnyException();

            verify(issueTypeSyncService, never()).syncIssueTypesForScope(anyLong());
        }
    }

    @Nested
    class NoActiveConnection {

        @Test
        void noActiveGithubConnection_runsUnrecordedFallback() {
            when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                            WORKSPACE_ID, IntegrationKind.GITHUB, IntegrationState.ACTIVE))
                    .thenReturn(Optional.empty());
            when(syncTargetProvider.getSyncSessions(IntegrationKind.GITHUB)).thenReturn(List.of(session()));

            scheduler.syncDataCron();

            verify(syncJobService, never()).run(any(), any());
            verify(issueTypeSyncService).syncIssueTypesForScope(WORKSPACE_ID);
        }
    }

    /**
     * The deletion sweep is the only thing separating a RECONCILIATION from an INITIAL pass; these
     * tests pin that difference to the job type.
     */
    @Nested
    class DeletionSweep {

        @Mock
        private SyncExecutionHandle handle;

        @BeforeEach
        void stubSession() {
            when(syncTargetProvider.getSyncSession(WORKSPACE_ID, IntegrationKind.GITHUB))
                    .thenReturn(Optional.of(session()));
            // Override the outer setUp's critical-rate-limit stub. That gate warns on its own, which
            // would make "did the sweep warn?" unanswerable — two warnings and no way to tell whose.
            lenient().when(rateLimitTracker.isCritical(WORKSPACE_ID)).thenReturn(false);
        }

        @Test
        void shouldSweepForDeletionsWhenReconciliation() {
            when(deletionSweepService.sweepScope(WORKSPACE_ID, handle))
                    .thenReturn(new GitHubDeletionSweepService.SweepOutcome(2, 1, false));

            scheduler.syncWorkspaceNow(WORKSPACE_ID, handle, SyncJobType.RECONCILIATION);

            verify(deletionSweepService).sweepScope(WORKSPACE_ID, handle);
            // A sweep that verified every repository is a clean pass, not a degraded one.
            verify(handle, never()).reportWarnings();
        }

        @Test
        void shouldNotSweepForDeletionsWhenInitial() {
            // A first sync has nothing stale to retire, and every row it has not fetched yet would look
            // deleted to a set difference — sweeping here would delete the mirror it is building.
            scheduler.syncWorkspaceNow(WORKSPACE_ID, handle, SyncJobType.INITIAL);

            verify(deletionSweepService, never()).sweepScope(anyLong(), any());
        }

        @Test
        void shouldReportWarningsWhenSweepCouldNotVerifyEveryRepository() {
            when(deletionSweepService.sweepScope(WORKSPACE_ID, handle))
                    .thenReturn(new GitHubDeletionSweepService.SweepOutcome(0, 0, true));

            scheduler.syncWorkspaceNow(WORKSPACE_ID, handle, SyncJobType.RECONCILIATION);

            // A reconciliation that could not verify a repository has not fully reconciled; saying so
            // is the difference between a degraded pass and a false clean bill of health.
            verify(handle).reportWarnings();
        }
    }
}
