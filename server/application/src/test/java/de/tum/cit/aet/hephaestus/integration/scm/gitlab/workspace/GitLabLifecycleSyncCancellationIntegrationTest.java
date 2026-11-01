package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
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
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncServiceHolder;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.GitLabIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.label.GitLabLabelSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.milestone.GitLabMilestoneSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabGroupSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabSyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.collaborator.GitLabCollaboratorSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.subissue.GitLabSubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamSyncService;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;

/** Runs the real initialization repository loop and job transactions; provider responses are controlled. */
class GitLabLifecycleSyncCancellationIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private GitLabProjectAdmission projectAdmission;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository monitorRepository;

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

    private final GitLabGroupSyncService groups = mock(GitLabGroupSyncService.class);
    private final GitLabIssueSyncService issues = mock(GitLabIssueSyncService.class);
    private final GitLabMergeRequestSyncService mergeRequests = mock(GitLabMergeRequestSyncService.class);
    private final GitLabTeamSyncService teams = mock(GitLabTeamSyncService.class);
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
        gitLabConfig = new ConnectionConfig.GitLabConfig(
                "https://gitlab.com", null, null, ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT, Set.of(), null);
        connection = new Connection(workspace, IntegrationKind.GITLAB, "initial-group", gitLabConfig);
        connection.setState(IntegrationState.ACTIVE);
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

        when(issues.syncIssues(anyLong(), any(Repository.class), any(), any())).thenAnswer(invocation -> {
            Repository repository = invocation.getArgument(1);
            processedRepositories.add(repository.getId());
            return SyncResult.completed(1);
        });
        when(mergeRequests.syncMergeRequests(anyLong(), any(Repository.class), any(), any()))
                .thenReturn(SyncResult.completed(1));
        when(teams.syncTeamsForGroup(workspace.getId(), workspace.getAccountLogin()))
                .thenReturn(new GitLabTeamSyncService.Result(0, true));
        when(groups.syncGroupProjects(workspace.getId(), workspace.getAccountLogin(), gitLabConfig.serverUrl()))
                .thenReturn(GitLabSyncResult.completed(List.of(first, second), 1, 0, 0));
        GitLabSyncServiceHolder services = new GitLabSyncServiceHolder(
                null, null, null, null, issues, mergeRequests, teams, null, null, null, null, null, null, null, null);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("services", services);
        trigger = triggerWith(beans.getBeanProvider(GitLabSyncServiceHolder.class));
    }

    @Test
    void shouldRecordCancelledWithoutAdvancingSuccessfulTimeWhenNextRepositoryIsSkipped() {
        when(issues.syncIssues(eq(workspace.getId()), any(Repository.class), any(), any()))
                .thenAnswer(invocation -> {
                    Repository repository = invocation.getArgument(1);
                    processedRepositories.add(repository.getId());
                    requestCancellation();
                    return SyncResult.completed(1);
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
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        ObjectProvider<GitLabSyncServiceHolder> services = beans.getBeanProvider(GitLabSyncServiceHolder.class);
        ObjectProvider<GitLabSyncServiceHolder> failingServices = mock();
        when(failingServices.getIfAvailable()).thenAnswer(invocation -> {
            requestCancellation();
            throw new IllegalStateException("Provider services unavailable");
        });
        assertThat(services.getIfAvailable()).isNull();
        trigger = triggerWith(failingServices);

        trigger.syncAllRepositories(workspace.getId());

        assertThat(processedRepositories).isEmpty();
        assertRepositoryUnchanged(first);
        assertRepositoryUnchanged(second);
        SyncJob job = lastJob();
        assertThat(job.isCancelRequested()).isTrue();
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.FAILED);
        assertThat(job.getErrorSummary()).contains("Provider services unavailable");
        assertThat(statusService
                        .getStatus(workspace.getId(), connection.getId())
                        .lastSuccessfulSyncAt())
                .isEqualTo(priorSuccessfulAt);
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
        trigger = triggerWith(
                providerFor(new GitLabSyncServiceHolder(
                        groups,
                        null,
                        null,
                        null,
                        issues,
                        mergeRequests,
                        teams,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)),
                activeDiscoveryConfig(),
                0);

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
    void shouldKeepCleanSuccessWhenDiscoveryCompletesAndNatsIsDeliberatelyDisabled() {
        trigger = triggerWith(
                providerFor(new GitLabSyncServiceHolder(
                        groups,
                        null,
                        null,
                        null,
                        issues,
                        mergeRequests,
                        teams,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)),
                activeDiscoveryConfig(),
                0);

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
        when(milestones.syncMilestonesForGroup(eq(workspace.getId()), eq(workspace.getAccountLogin()), any(), any()))
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
        trigger = triggerWith(
                providerFor(new GitLabSyncServiceHolder(
                        groups,
                        null,
                        labels,
                        milestones,
                        issues,
                        mergeRequests,
                        teams,
                        collaborators,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)),
                activeDiscoveryConfig(),
                0);

        trigger.syncAllRepositories(workspace.getId());
        SyncJob incomplete = lastJob();
        RepositoryToMonitor firstPass = monitor(first);
        RepositoryToMonitor siblingPass = monitor(second);
        assertRepositoryCompleted(first);
        assertRepositoryCompleted(second);

        trigger.syncAllRepositories(workspace.getId());
        SyncJob recovered = lastJob();
        RepositoryToMonitor recovery = monitor(first);

        assertSoftly(softly -> {
            softly.assertThat(phaseTimestamp(firstPass, failedPhase)).isEqualTo(priorPhaseAt);
            softly.assertThat(incomplete.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
            softly.assertThat(firstPass.getIssuesSyncedAt()).isAfter(priorPhaseAt);
            softly.assertThat(firstPass.getPullRequestsSyncedAt()).isAfter(priorPhaseAt);
            // FULL_REPOSITORY retains its existing issues/MRs boundary despite the secondary failure.
            softly.assertThat(firstPass.getRepositorySyncedAt()).isAfter(priorPhaseAt);
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
        trigger = triggerWith(
                providerFor(new GitLabSyncServiceHolder(
                        groups,
                        null,
                        null,
                        null,
                        issues,
                        mergeRequests,
                        teams,
                        null,
                        null,
                        null,
                        null,
                        null,
                        subIssues,
                        null,
                        null)),
                activeDiscoveryConfig(),
                0);

        trigger.syncAllRepositories(workspace.getId());

        assertRepositoryCompleted(first);
        assertRepositoryCompleted(second);
        assertThat(lastJob().getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
    }

    @Test
    void shouldKeepConsumedCancellationWhenDiscoveryAlreadyReportedWarnings() {
        when(groups.syncGroupProjects(workspace.getId(), workspace.getAccountLogin(), gitLabConfig.serverUrl()))
                .thenReturn(GitLabSyncResult.withErrors(List.of(first), 1, 1, 0, 0));
        when(issues.syncIssues(eq(workspace.getId()), any(Repository.class), any(), any()))
                .thenAnswer(invocation -> {
                    Repository repository = invocation.getArgument(1);
                    processedRepositories.add(repository.getId());
                    requestCancellation();
                    return SyncResult.completed(1);
                });
        trigger = triggerWith(
                providerFor(new GitLabSyncServiceHolder(
                        groups,
                        null,
                        null,
                        null,
                        issues,
                        mergeRequests,
                        teams,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)),
                activeDiscoveryConfig(),
                0);

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

    private ObjectProvider<GitLabSyncServiceHolder> providerFor(GitLabSyncServiceHolder services) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("services", services);
        return beans.getBeanProvider(GitLabSyncServiceHolder.class);
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

    private GitLabWorkspaceDataSyncTrigger triggerWith(ObjectProvider<GitLabSyncServiceHolder> services) {
        // Discovery is outside this controlled-loop proof; syncFullData and its persisted lookup run unchanged.
        return triggerWith(services, mock(ConnectionService.class), 15);
    }

    private GitLabWorkspaceDataSyncTrigger triggerWith(
            ObjectProvider<GitLabSyncServiceHolder> services, ConnectionService discoveryConfig, int cooldownMinutes) {
        StaticListableBeanFactory absent = new StaticListableBeanFactory();
        GitLabWorkspaceInitializationService initialization = new GitLabWorkspaceInitializationService(
                workspaceRepository,
                organizationRepository,
                monitorRepository,
                repositoryRepository,
                new NatsConnectionProperties(
                        false,
                        "nats://localhost:4222",
                        null,
                        new NatsConnectionProperties.Consumer(Duration.ofSeconds(60))),
                new SyncSchedulerProperties(
                        true,
                        7,
                        "0 0 3 * * *",
                        cooldownMinutes,
                        new SyncSchedulerProperties.BackfillProperties(false, 50, 100, 60),
                        new SyncSchedulerProperties.FilterProperties(Set.of(), Set.of())),
                absent.getBeanProvider(IntegrationNatsConsumer.class),
                syncTargetProvider,
                services,
                absent.getBeanProvider(GitLabWebhookService.class),
                absent.getBeanProvider(GitLabRateLimitTracker.class),
                absent.getBeanProvider(GitLabWorkspaceDataSyncTrigger.class),
                discoveryConfig,
                workspaceLinkService,
                repositoryMonitors,
                actorSelector,
                new ConcurrentTaskExecutor(new SyncTaskExecutor()),
                projectAdmission);
        return new GitLabWorkspaceDataSyncTrigger(
                initialization, workspaceRepository, connectionRepository, jobService);
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
