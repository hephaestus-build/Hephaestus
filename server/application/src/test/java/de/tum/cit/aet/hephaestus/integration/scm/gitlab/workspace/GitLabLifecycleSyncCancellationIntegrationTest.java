package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.activity.spi.ActivityLedgerRepair;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.RecentSyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobStatus;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.core.sync.api.SyncStatusService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabProjectResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.GitLabIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.label.GitLabLabelSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.milestone.GitLabMilestoneSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabGroupSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabSyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.GitLabProjectSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.collaborator.GitLabCollaboratorSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.subissue.GitLabSubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.GitLabDeletionSweepService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.GitLabRepositorySync;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.GitLabScopeSync;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamSyncService;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceLookup;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;

/** Runs the lifecycle trigger over the one GitLab sync body and real job transactions; provider responses are controlled. */
class GitLabLifecycleSyncCancellationIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private ConnectionQueries connectionQueries;

    @Autowired
    private GitLabProjectAdmission projectAdmission;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository monitorRepository;

    @Autowired
    private WorkspaceLookup workspaceLookup;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private SyncTargetProvider syncTargetProvider;

    @Autowired
    private SyncJobService jobService;

    @Autowired
    private SyncJobRepository jobRepository;

    @Autowired
    private SyncStatusService statusService;

    @Autowired
    private GitLabWorkspaceLinkService workspaceLinkService;

    @Autowired
    private GitLabRepositoryMonitors repositoryMonitors;

    @Autowired
    private WorkspaceActorSelector actorSelector;

    @Autowired
    private ActivityLedgerRepair activityLedgerRepair;

    @Autowired
    private SyncContextProvider syncContextProvider;

    @Autowired
    private SyncSchedulerProperties syncSchedulerProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final GitLabGroupSyncService groups = mock(GitLabGroupSyncService.class);
    private final GitLabIssueSyncService issues = mock(GitLabIssueSyncService.class);
    private final GitLabMergeRequestSyncService mergeRequests = mock(GitLabMergeRequestSyncService.class);
    private final GitLabTeamSyncService teams = mock(GitLabTeamSyncService.class);
    private final ConnectionService discoveryConfig = mock(ConnectionService.class);
    private GitLabProjectSyncService projects = mock(GitLabProjectSyncService.class);
    // The sync services a test adds; each one it leaves null skips its phase.
    private @Nullable GitLabGroupSyncService groupSync;
    private @Nullable GitLabLabelSyncService labelSync;
    private @Nullable GitLabMilestoneSyncService milestoneSync;
    private @Nullable GitLabCollaboratorSyncService collaboratorSync;
    private @Nullable GitLabSubIssueSyncService subIssueSync;
    private final List<Long> processedRepositories = new ArrayList<>();
    private Workspace workspace;
    private Connection connection;
    private ConnectionConfig.GitLabConfig gitLabConfig;
    private Repository first;
    private Repository second;
    private GitLabWorkspaceDataSyncTrigger trigger;
    private Instant priorSuccessfulAt;

    @BeforeEach
    void setUp() {
        workspace = createWorkspace(
                "initial-cancel",
                "Initial cancellation",
                "initial-group",
                AccountType.ORG,
                persistUser("initial-owner"));
        gitLabConfig = new ConnectionConfig.GitLabConfig("https://gitlab.com", 42L, null, Set.of(), null);
        connection = new Connection(workspace, IntegrationKind.GITLAB, "initial-group", gitLabConfig);
        ConnectionFixtures.reachState(connection, IntegrationState.ACTIVE);
        connection = connectionRepository.saveAndFlush(connection);
        first = monitoredRepository("initial-group/a", 901L);
        second = monitoredRepository("initial-group/b", 902L);
        assertThat(repositoryRepository.findAllByWorkspaceMonitors(workspace.getId()))
                .extracting(Repository::getId)
                .containsExactly(first.getId(), second.getId());

        jobService.run(
                new SyncJobRequest(
                        workspace.getId(),
                        connection.getId(),
                        IntegrationKind.GITLAB,
                        SyncJobType.RECONCILIATION,
                        SyncJobTrigger.MANUAL,
                        null),
                handle -> {});
        priorSuccessfulAt = Objects.requireNonNull(
                statusService.getStatus(workspace.getId(), connection.getId()).lastSuccessfulSyncAt());

        when(issues.syncIssues(anyLong(), any(Repository.class), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Repository repository = invocation.getArgument(1);
                    processedRepositories.add(repository.getId());
                    return new RecentSyncResult(SyncResult.completed(1), null);
                });
        when(mergeRequests.syncMergeRequests(anyLong(), any(Repository.class), any(), any(), any()))
                .thenReturn(new RecentSyncResult(SyncResult.completed(1), null));
        when(teams.syncTeamsForGroup(workspace.getId(), workspace.getAccountLogin()))
                .thenReturn(new GitLabTeamSyncService.Result(0, true));
        when(groups.syncGroupProjects(workspace.getId(), workspace.getAccountLogin(), gitLabConfig.serverUrl()))
                .thenReturn(GitLabSyncResult.completed(List.of(first, second), 1, 0, 0));
        trigger = triggerWith(syncTargetProvider, discoveryConfig);
    }

    /** A restart within the cooldown of the scope's last successful sync asks the provider for nothing. */
    @Test
    void shouldCallNoProviderWhenTheServerStartsWithinTheCooldown() {
        assertThat(syncSchedulerProperties.withinCooldown(priorSuccessfulAt, Instant.now()))
                .isTrue();

        trigger.syncOnStartup(workspace.getId(), true);

        verifyNoInteractions(discoveryConfig, projects, issues, mergeRequests, teams);
        assertRepositoryUnchanged(first);
        assertRepositoryUnchanged(second);
        assertThat(jobRepository.findAll())
                .filteredOn(job -> job.getConnection().getId().equals(connection.getId()))
                .singleElement()
                .extracting(SyncJob::getType)
                .isEqualTo(SyncJobType.RECONCILIATION);
    }

    @Test
    void shouldSyncWhenTheServerStartsAfterTheCooldown() {
        ageJobsBeyondTheCooldown();

        trigger.syncOnStartup(workspace.getId(), true);

        assertThat(processedRepositories).containsExactly(first.getId(), second.getId());
        assertRepositoryCompleted(first);
        assertRepositoryCompleted(second);
        assertThat(lastJob().getType()).isEqualTo(SyncJobType.INITIAL);
        assertThat(lastJob().getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
    }

    /** A backfill reads only older history, so a recent one does not count as bringing the scope up to date. */
    @Test
    void shouldSyncWhenTheServerStartsAfterOnlyABackfillWithinTheCooldown() {
        ageJobsBeyondTheCooldown();
        jobService.run(
                new SyncJobRequest(
                        workspace.getId(),
                        connection.getId(),
                        IntegrationKind.GITLAB,
                        SyncJobType.BACKFILL,
                        SyncJobTrigger.MANUAL,
                        null),
                handle -> {});

        trigger.syncOnStartup(workspace.getId(), true);

        assertThat(processedRepositories).containsExactly(first.getId(), second.getId());
        assertThat(lastJob().getType()).isEqualTo(SyncJobType.INITIAL);
        assertThat(lastJob().getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
    }

    /** A new connection syncs whatever its history, so the cooldown never delays its first data. */
    @Test
    void shouldSyncANewConnectionWithinTheCooldown() {
        assertThat(syncSchedulerProperties.withinCooldown(priorSuccessfulAt, Instant.now()))
                .isTrue();

        trigger.syncAllRepositories(workspace.getId());

        assertThat(processedRepositories).containsExactly(first.getId(), second.getId());
        assertRepositoryCompleted(first);
        assertRepositoryCompleted(second);
        assertThat(lastJob().getType()).isEqualTo(SyncJobType.INITIAL);
        assertSuccessfulJob();
    }

    @Test
    void shouldRecordCancelledWithoutAdvancingSuccessfulTimeWhenNextRepositoryIsSkipped() {
        when(issues.syncIssues(eq(workspace.getId()), any(Repository.class), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Repository repository = invocation.getArgument(1);
                    processedRepositories.add(repository.getId());
                    requestCancellation();
                    return new RecentSyncResult(SyncResult.completed(1), null);
                });

        trigger.syncAllRepositories(workspace.getId());

        assertThat(processedRepositories).containsExactly(first.getId());
        assertRepositoryCompleted(first);
        assertRepositoryUnchanged(second);
        SyncJob job = lastJob();
        assertThat(job.isCancelRequested()).isTrue();
        assertThat(job.getErrorSummary()).isNull();
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.CANCELLED);
        assertThat(statusService
                        .getStatus(workspace.getId(), connection.getId())
                        .lastSuccessfulSyncAt())
                .isEqualTo(priorSuccessfulAt);
    }

    @Test
    void shouldKeepSuccessfulCompletionWhenNoCancellationIsRequested() {
        trigger.syncAllRepositories(workspace.getId());

        assertThat(processedRepositories).containsExactly(first.getId(), second.getId());
        assertRepositoryCompleted(first);
        assertRepositoryCompleted(second);
        assertSuccessfulJob();
        assertThat(lastJob().isCancelRequested()).isFalse();
    }

    @Test
    void shouldKeepSuccessfulCompletionWhenCancellationArrivesAfterLastRepositoryCheckpoint() {
        when(teams.syncTeamsForGroup(workspace.getId(), workspace.getAccountLogin()))
                .thenAnswer(invocation -> {
                    requestCancellation();
                    return new GitLabTeamSyncService.Result(0, true);
                });

        trigger.syncAllRepositories(workspace.getId());

        assertThat(processedRepositories).containsExactly(first.getId(), second.getId());
        assertRepositoryCompleted(first);
        assertRepositoryCompleted(second);
        assertThat(lastJob().isCancelRequested()).isTrue();
        assertSuccessfulJob();
    }

    @Test
    void shouldKeepRealFailureWhenCancellationWasRequestedButNoCheckpointStoppedWork() {
        SyncTargetProvider failingTargets = mock(SyncTargetProvider.class, delegatesTo(syncTargetProvider));
        doAnswer(invocation -> {
                    requestCancellation();
                    throw new IllegalStateException("Sync eligibility check failed");
                })
                .when(failingTargets)
                .isScopeActiveForSync(workspace.getId());
        trigger = triggerWith(failingTargets, mock(ConnectionService.class));

        trigger.syncAllRepositories(workspace.getId());

        assertThat(processedRepositories).isEmpty();
        assertRepositoryUnchanged(first);
        assertRepositoryUnchanged(second);
        SyncJob job = lastJob();
        assertThat(job.isCancelRequested()).isTrue();
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.FAILED);
        assertThat(job.getErrorSummary()).contains("Sync eligibility check failed");
        assertThat(statusService
                        .getStatus(workspace.getId(), connection.getId())
                        .lastSuccessfulSyncAt())
                .isEqualTo(priorSuccessfulAt);
    }

    /** A scope that cannot sync after initialization warns and asks the provider for nothing. */
    @Test
    void shouldWarnAndCallNoProviderWhenTheScopeCannotSyncAfterInitialization() {
        SyncTargetProvider refusedTargets = mock(SyncTargetProvider.class, delegatesTo(syncTargetProvider));
        doReturn(Optional.empty()).when(refusedTargets).getSyncSession(workspace.getId(), IntegrationKind.GITLAB);
        trigger = triggerWith(refusedTargets, mock(ConnectionService.class));

        trigger.syncAllRepositories(workspace.getId());

        assertThat(processedRepositories).isEmpty();
        verifyNoInteractions(teams);
        assertRepositoryUnchanged(first);
        assertRepositoryUnchanged(second);
        SyncJob job = lastJob();
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
        assertThat(job.getErrorSummary()).isNull();
        assertThat(job.getProgress())
                .containsEntry(SyncProgress.KEY_CURRENT_STEP, "Sync eligibility check did not complete.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"partial", "unavailable"})
    void shouldRecordWarningsAndKeepPrimaryProgressWhenDiscoveryIsIncomplete(String discovery) {
        if (discovery.equals("partial")) {
            when(groups.syncGroupProjects(workspace.getId(), workspace.getAccountLogin(), gitLabConfig.serverUrl()))
                    .thenReturn(GitLabSyncResult.withErrors(List.of(first), 1, 1, 0, 0));
        } else {
            when(groups.syncGroupProjects(workspace.getId(), workspace.getAccountLogin(), gitLabConfig.serverUrl()))
                    .thenThrow(new IllegalStateException("Project discovery unavailable"));
        }
        groupSync = groups;
        trigger = triggerWith(syncTargetProvider, activeDiscoveryConfig());

        trigger.syncAllRepositories(workspace.getId());

        assertRepositoryCompleted(first);
        assertRepositoryCompleted(second);
        SyncJob job = lastJob();
        assertSoftly(softly -> {
            softly.assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
            softly.assertThat(job.getType()).isEqualTo(SyncJobType.INITIAL);
            softly.assertThat(job.getTrigger()).isEqualTo(SyncJobTrigger.LIFECYCLE);
            softly.assertThat(job.getErrorSummary()).isNull();
        });
        assertThat(processedRepositories).containsExactly(first.getId(), second.getId());
    }

    @Test
    void shouldKeepCleanSuccessWhenDiscoveryCompletesWithTheProviderInbox() {
        groupSync = groups;
        trigger = triggerWith(syncTargetProvider, activeDiscoveryConfig());

        trigger.syncAllRepositories(workspace.getId());

        assertRepositoryCompleted(first);
        assertRepositoryCompleted(second);
        assertSuccessfulJob();
        assertThat(processedRepositories).containsExactly(first.getId(), second.getId());
    }

    @ParameterizedTest
    @EnumSource(
            value = SyncTargetProvider.SyncType.class,
            names = {"LABELS", "MILESTONES", "COLLABORATORS"})
    void shouldPreserveFailedPhaseWatermarkAndWarnThenRecoverWhenTypedPhaseAborts(
            SyncTargetProvider.SyncType failedPhase) {
        var phases = List.of(
                SyncTargetProvider.SyncType.LABELS,
                SyncTargetProvider.SyncType.MILESTONES,
                SyncTargetProvider.SyncType.COLLABORATORS);
        Instant priorPhaseAt = Instant.parse("2020-01-01T00:00:00Z");
        for (Repository repository : List.of(first, second)) {
            Long monitorId = monitor(repository).getId();
            for (var phase : phases) {
                syncTargetProvider.updateSyncTimestamp(monitorId, phase, priorPhaseAt);
            }
        }
        var labels = mock(GitLabLabelSyncService.class);
        var milestones = mock(GitLabMilestoneSyncService.class);
        var collaborators = mock(GitLabCollaboratorSyncService.class);
        when(labels.syncLabelsForRepository(anyLong(), any(Repository.class), any()))
                .thenReturn(SyncResult.completed(1));
        when(milestones.syncMilestonesForRepository(anyLong(), any(Repository.class), any()))
                .thenReturn(SyncResult.completed(1));
        when(milestones.syncMilestonesForGroup(eq(workspace.getId()), eq(workspace.getAccountLogin()), any()))
                .thenReturn(SyncResult.completed(0));
        when(collaborators.syncCollaboratorsForRepository(anyLong(), any(Repository.class), any()))
                .thenReturn(SyncResult.completed(1));
        when(labels.syncLabelsForRepository(eq(workspace.getId()), eq(first), any()))
                .thenReturn(
                        failedPhase == SyncTargetProvider.SyncType.LABELS
                                ? SyncResult.abortedError(1)
                                : SyncResult.completed(1),
                        SyncResult.completed(1));
        when(milestones.syncMilestonesForRepository(eq(workspace.getId()), eq(first), any()))
                .thenReturn(
                        failedPhase == SyncTargetProvider.SyncType.MILESTONES
                                ? SyncResult.abortedError(1)
                                : SyncResult.completed(1),
                        SyncResult.completed(1));
        when(collaborators.syncCollaboratorsForRepository(eq(workspace.getId()), eq(first), any()))
                .thenReturn(
                        failedPhase == SyncTargetProvider.SyncType.COLLABORATORS
                                ? SyncResult.abortedError(1)
                                : SyncResult.completed(1),
                        SyncResult.completed(1));
        groupSync = groups;
        labelSync = labels;
        milestoneSync = milestones;
        collaboratorSync = collaborators;
        trigger = triggerWith(syncTargetProvider, activeDiscoveryConfig());

        trigger.syncAllRepositories(workspace.getId());
        SyncJob incomplete = lastJob();
        RepositoryToMonitor firstPass = monitor(first);
        RepositoryToMonitor siblingPass = monitor(second);
        // A failed phase holds the repository's watermark, so the next run reads the repository again.
        assertThat(repositoryRepository.findById(first.getId()).orElseThrow().getLastSyncAt())
                .isNull();
        assertThat(firstPass.getSyncErrorSummary()).isNotNull();
        assertRepositoryCompleted(second);

        trigger.syncAllRepositories(workspace.getId());
        SyncJob recovered = lastJob();
        RepositoryToMonitor recovery = monitor(first);

        assertSoftly(softly -> {
            softly.assertThat(phaseTimestamp(firstPass, failedPhase)).isEqualTo(priorPhaseAt);
            softly.assertThat(incomplete.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
            softly.assertThat(firstPass.getIssuesSyncedAt()).isAfter(priorPhaseAt);
            softly.assertThat(firstPass.getPullRequestsSyncedAt()).isAfter(priorPhaseAt);
            softly.assertThat(firstPass.getRepositorySyncedAt()).isNull();
            for (var phase : phases) {
                if (phase != failedPhase) {
                    softly.assertThat(phaseTimestamp(firstPass, phase)).isAfter(priorPhaseAt);
                }
                softly.assertThat(phaseTimestamp(siblingPass, phase)).isAfter(priorPhaseAt);
                softly.assertThat(phaseTimestamp(recovery, phase)).isAfter(priorPhaseAt);
            }
            softly.assertThat(recovered.getId()).isNotEqualTo(incomplete.getId());
            softly.assertThat(recovered.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
        });
        assertRepositoryCompleted(first);
        assertSuccessfulJob();
        assertThat(processedRepositories).containsExactly(first.getId(), second.getId(), first.getId(), second.getId());
    }

    @Test
    void shouldRecordWarningsWithoutErasingPrimaryProgressWhenPostRepositoryPhaseAborts() {
        var subIssues = mock(GitLabSubIssueSyncService.class);
        when(subIssues.syncSubIssuesForRepository(anyLong(), any(Repository.class), any()))
                .thenReturn(SyncResult.completed(0));
        when(subIssues.syncSubIssuesForRepository(eq(workspace.getId()), eq(first), any()))
                .thenReturn(SyncResult.abortedError(1));
        groupSync = groups;
        subIssueSync = subIssues;
        trigger = triggerWith(syncTargetProvider, activeDiscoveryConfig());

        trigger.syncAllRepositories(workspace.getId());

        // The repository phases finished, so the watermarks move; the failed post-repository phase stays recorded.
        assertThat(repositoryRepository.findById(first.getId()).orElseThrow().getLastSyncAt())
                .isNotNull();
        assertThat(monitor(first).getIssuesSyncedAt()).isNotNull();
        assertThat(monitor(first).getPullRequestsSyncedAt()).isNotNull();
        assertThat(monitor(first).getRepositorySyncedAt()).isNotNull();
        assertThat(monitor(first).getSyncErrorSummary()).contains("Sub-issue sync");
        assertRepositoryCompleted(second);
        assertThat(lastJob().getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
    }

    @Test
    void shouldKeepConsumedCancellationWhenDiscoveryAlreadyReportedWarnings() {
        when(groups.syncGroupProjects(workspace.getId(), workspace.getAccountLogin(), gitLabConfig.serverUrl()))
                .thenReturn(GitLabSyncResult.withErrors(List.of(first), 1, 1, 0, 0));
        when(issues.syncIssues(eq(workspace.getId()), any(Repository.class), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Repository repository = invocation.getArgument(1);
                    processedRepositories.add(repository.getId());
                    requestCancellation();
                    return new RecentSyncResult(SyncResult.completed(1), null);
                });
        groupSync = groups;
        trigger = triggerWith(syncTargetProvider, activeDiscoveryConfig());

        trigger.syncAllRepositories(workspace.getId());

        assertRepositoryCompleted(first);
        assertRepositoryUnchanged(second);
        SyncJob job = lastJob();
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.CANCELLED);
        assertThat(job.isCancelRequested()).isTrue();
        assertThat(job.getErrorSummary()).isNull();
        assertThat(statusService
                        .getStatus(workspace.getId(), connection.getId())
                        .lastSuccessfulSyncAt())
                .isEqualTo(priorSuccessfulAt);
    }

    private ConnectionService activeDiscoveryConfig() {
        ConnectionService config = mock(ConnectionService.class);
        when(config.findActiveGitLabConfig(workspace.getId())).thenReturn(Optional.of(gitLabConfig));
        when(config.findActiveBearerToken(workspace.getId(), IntegrationKind.GITLAB))
                .thenReturn(Optional.of(new BearerToken("glpat-test-token", null)));
        return config;
    }

    private RepositoryToMonitor monitor(Repository repository) {
        return monitorRepository
                .findByWorkspaceIdAndNameWithOwner(workspace.getId(), repository.getNameWithOwner())
                .orElseThrow();
    }

    private Instant phaseTimestamp(RepositoryToMonitor monitor, SyncTargetProvider.SyncType phase) {
        return switch (phase) {
            case LABELS -> monitor.getLabelsSyncedAt();
            case MILESTONES -> monitor.getMilestonesSyncedAt();
            case COLLABORATORS -> monitor.getCollaboratorsSyncedAt();
            default -> throw new IllegalArgumentException("Not a secondary phase watermark: " + phase);
        };
    }

    /**
     * The lifecycle trigger over issues, merge requests, teams and the sync services this test set. A {@code mock}
     * discovery configuration keeps discovery outside this controlled-loop proof. Each project's metadata answers from
     * its stored row.
     */
    private GitLabWorkspaceDataSyncTrigger triggerWith(SyncTargetProvider targets, ConnectionService discoveryConfig) {
        StaticListableBeanFactory absent = new StaticListableBeanFactory();
        GitLabWorkspaceInitializationService initialization = new GitLabWorkspaceInitializationService(
                workspaceLookup,
                groupSync,
                absent.getBeanProvider(GitLabWebhookService.class),
                absent.getBeanProvider(GitLabWorkspaceDataSyncTrigger.class),
                discoveryConfig,
                workspaceLinkService,
                repositoryMonitors,
                new ConcurrentTaskExecutor(new SyncTaskExecutor()));
        projects = mock(GitLabProjectSyncService.class);
        for (Repository repository : List.of(first, second)) {
            var metadata = mock(GitLabProjectResponse.class);
            when(projects.fetchProjectById(anyLong(), eq(repository.getNativeId())))
                    .thenReturn(Optional.of(metadata));
            when(projects.persistProject(eq(metadata), any()))
                    .thenAnswer(invocation -> repositoryRepository.findById(repository.getId()));
        }
        var repositorySync = new GitLabRepositorySync(
                targets,
                repositoryRepository,
                null,
                actorSelector,
                projects,
                activityLedgerRepair,
                projectAdmission,
                labelSync,
                milestoneSync,
                issues,
                mergeRequests,
                collaboratorSync,
                null,
                null,
                null,
                subIssueSync,
                null);
        var scopeSync = new GitLabScopeSync(
                targets,
                syncContextProvider,
                mock(GitLabDeletionSweepService.class),
                initialization,
                repositoryMonitors,
                workspaceLookup,
                repositorySync,
                groupSync,
                null,
                null,
                teams,
                null);
        return new GitLabWorkspaceDataSyncTrigger(
                initialization, scopeSync, workspaceLookup, connectionQueries, jobService, syncSchedulerProperties);
    }

    private Repository monitoredRepository(String name, long nativeId) {
        Repository repository = new Repository();
        repository.setNativeId(nativeId);
        repository.setProvider(ensureGitLabProvider());
        repository.setName(name.substring(name.lastIndexOf('/') + 1));
        repository.setNameWithOwner(name);
        repository.setHtmlUrl("https://gitlab.com/" + name);
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        repository = repositoryRepository.saveAndFlush(repository);
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNativeId(nativeId);
        monitor.setNameWithOwner(name);
        monitorRepository.saveAndFlush(monitor);
        return repository;
    }

    private void ageJobsBeyondTheCooldown() {
        jdbcTemplate.update(
                "UPDATE sync_job SET finished_at = ? WHERE connection_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(syncSchedulerProperties.cooldownMinutes() + 1))),
                connection.getId());
    }

    private void requestCancellation() {
        SyncJob active = jobRepository
                .findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(connection.getId(), SyncJobStatus.ACTIVE)
                .orElseThrow();
        jobService.requestCancel(workspace.getId(), Objects.requireNonNull(active.getId()));
    }

    private SyncJob lastJob() {
        return jobRepository
                .findFirstByConnection_IdAndStatusInOrderByFinishedAtDesc(connection.getId(), SyncJobStatus.TERMINAL)
                .orElseThrow();
    }

    private void assertSuccessfulJob() {
        SyncJob job = lastJob();
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
        assertThat(job.getErrorSummary()).isNull();
        assertThat(statusService
                        .getStatus(workspace.getId(), connection.getId())
                        .lastSuccessfulSyncAt())
                .isEqualTo(job.getFinishedAt())
                .isAfter(priorSuccessfulAt);
    }

    private void assertRepositoryCompleted(Repository repository) {
        assertThat(repositoryRepository
                        .findById(repository.getId())
                        .orElseThrow()
                        .getLastSyncAt())
                .isNotNull();
        RepositoryToMonitor monitor = monitorRepository.findByWorkspaceId(workspace.getId()).stream()
                .filter(candidate -> candidate.getNameWithOwner().equals(repository.getNameWithOwner()))
                .findFirst()
                .orElseThrow();
        assertThat(monitor.getIssuesSyncedAt()).isNotNull();
        assertThat(monitor.getPullRequestsSyncedAt()).isNotNull();
        assertThat(monitor.getRepositorySyncedAt()).isNotNull();
        assertThat(monitor.getSyncErrorSummary()).isNull();
    }

    private void assertRepositoryUnchanged(Repository repository) {
        assertThat(repositoryRepository
                        .findById(repository.getId())
                        .orElseThrow()
                        .getLastSyncAt())
                .isNull();
        RepositoryToMonitor monitor = monitorRepository.findByWorkspaceId(workspace.getId()).stream()
                .filter(candidate -> candidate.getNameWithOwner().equals(repository.getNameWithOwner()))
                .findFirst()
                .orElseThrow();
        assertThat(monitor.getIssuesSyncedAt()).isNull();
        assertThat(monitor.getPullRequestsSyncedAt()).isNull();
        assertThat(monitor.getRepositorySyncedAt()).isNull();
        assertThat(monitor.getSyncErrorSummary()).isNull();
    }
}
