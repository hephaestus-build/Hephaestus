package de.tum.cit.aet.hephaestus.integration.scm.github.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.activity.spi.ActivityLedgerRepair;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationTokenProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener;
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
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.commit.CommitAuthorEnrichmentService;
import de.tum.cit.aet.hephaestus.integration.scm.github.commit.CommitMetadataEnrichmentService;
import de.tum.cit.aet.hephaestus.integration.scm.github.commit.GitHubCommitBackfillService;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.RateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuedependency.GitHubIssueDependencySyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuetype.GitHubIssueTypeSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.GitHubLabelSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.milestone.GitHubMilestoneSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.organization.GitHubOrganizationSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.GitHubRepositorySyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.collaborator.GitHubCollaboratorSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.subissue.GitHubSubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.team.GitHubTeamSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.workspace.GitHubWorkspaceDataSyncTrigger;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.WorkspaceRepositoryMonitorService;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;

/** Real lifecycle trigger, full repository bodies and row writes; only provider responses are controlled. */
class GitHubLifecycleSyncCancellationIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository monitorRepository;

    @Autowired
    private SyncTargetProvider syncTargets;

    @Autowired
    private InstallationTokenProvider tokenProvider;

    @Autowired
    private SyncJobService jobs;

    @Autowired
    private SyncJobRepository jobRepository;

    @Autowired
    private SyncStatusService status;

    @Autowired
    private GitHubExceptionClassifier exceptionClassifier;

    private final GitHubRepositorySyncService metadata = mock(GitHubRepositorySyncService.class);
    private final GitHubIssueSyncService issues = mock(GitHubIssueSyncService.class);
    private final GitHubPullRequestSyncService pullRequests = mock(GitHubPullRequestSyncService.class);
    private final GitHubTeamSyncService teams = mock(GitHubTeamSyncService.class);
    private final RateLimitTracker rateLimit = mock(RateLimitTracker.class);
    private final List<Long> processed = new ArrayList<>();
    private Workspace workspace;
    private Repository first;
    private Repository second;

    @BeforeEach
    void setUp() {
        workspace = createWorkspace(
                "github-initial-cancel",
                "Initial cancellation",
                "initial-group",
                AccountType.ORG,
                persistUser("initial-owner"));
        first = monitoredRepository("initial-group/a", 901L);
        second = monitoredRepository("initial-group/b", 902L);
        when(metadata.syncRepository(anyLong(), any(), any(), any())).thenAnswer(invocation -> {
            String name = invocation.getArgument(1);
            return Optional.of(name.equals(first.getNameWithOwner()) ? first : second);
        });
        when(issues.syncForRepository(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            processed.add(invocation.getArgument(1));
            return SyncResult.completed(1);
        });
        when(pullRequests.syncForRepository(any(), any(), any(), any(), any())).thenReturn(SyncResult.completed(1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldKeepCompletedProgressWithoutSuccessfulTimeWhenNextRepositoryIsCancelled(boolean app) {
        Run run = connect(app);
        doAnswer(invocation -> {
                    processed.add(invocation.getArgument(1));
                    requestCancellation(run.connection());
                    return SyncResult.completed(1);
                })
                .when(issues)
                .syncForRepository(any(), any(), any(), any(), any());

        run.trigger().syncAllRepositories(workspace.getId());

        assertThat(processed).hasSize(1);
        Repository completed = processed.getFirst().equals(first.getId()) ? first : second;
        Repository skipped = completed.getId().equals(first.getId()) ? second : first;
        assertCompleted(completed);
        assertUntouched(skipped);
        assertCancelled(run);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRecordCancelledWhenRateLimitWaitStopsBeforeRepositoryWork(boolean app) {
        Run run = connect(app);
        AtomicBoolean firstObservation = new AtomicBoolean(true);
        when(rateLimit.isCritical(workspace.getId())).thenAnswer(invocation -> {
            if (firstObservation.getAndSet(false)) {
                requestCancellation(run.connection());
                return true;
            }
            return false;
        });

        run.trigger().syncAllRepositories(workspace.getId());

        assertThat(processed).isEmpty();
        assertUntouched(first);
        assertUntouched(second);
        assertCancelled(run);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRecordSuccessfulCompletionWhenAllRepositoriesComplete(boolean app) {
        Run run = connect(app);
        run.trigger().syncAllRepositories(workspace.getId());

        assertBothCompleted();
        assertThat(lastJob(run.connection()).isCancelRequested()).isFalse();
        assertSuccessful(run);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRecordWarningsAndRecoverWhenFullSyncHasAnIncompleteIssuePhase(boolean app) {
        Run run = connect(app);
        stubIssueOutcome(first, SyncResult.abortedError(0));

        run.trigger().syncAllRepositories(workspace.getId());

        assertThat(processed).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertIssuesIncomplete(first);
        assertCompleted(second);
        RepositoryToMonitor completedSibling = monitor(second);
        SyncJob warning = assertWarnings(run);

        stubIssueOutcome(first, SyncResult.completed(1));
        run.trigger().syncAllRepositories(workspace.getId());

        assertThat(processed).containsExactlyInAnyOrder(first.getId(), second.getId(), first.getId());
        assertCompleted(first);
        assertSameProgress(second, completedSibling);
        assertSuccessfulAfter(run, warning);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRecordWarningsAndRecoverWhenSingleTargetSyncHasAnIncompleteIssuePhase(boolean app) {
        Run run = connect(app);
        long syncTargetId = Objects.requireNonNull(monitor(first).getId());
        stubIssueOutcome(first, SyncResult.abortedError(0));

        run.trigger().syncSingleSyncTarget(syncTargetId);

        assertThat(processed).containsExactly(first.getId());
        assertIssuesIncomplete(first);
        assertUntouched(second);
        SyncJob warning = assertWarnings(run);

        stubIssueOutcome(first, SyncResult.completed(1));
        run.trigger().syncSingleSyncTarget(syncTargetId);

        assertThat(processed).containsExactly(first.getId(), first.getId());
        assertCompleted(first);
        assertUntouched(second);
        assertSuccessfulAfter(run, warning);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRemainSuccessfulWithoutRepositoryWorkWhenFullSyncTargetsAreInCooldown(boolean app) {
        Run run = connect(app);
        run.trigger().syncAllRepositories(workspace.getId());

        assertBothCompleted();
        assertSuccessful(run);
        RepositoryToMonitor completedFirst = monitor(first);
        RepositoryToMonitor completedSecond = monitor(second);
        SyncJob completed = lastJob(run.connection());

        run.trigger().syncAllRepositories(workspace.getId());

        assertThat(processed).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertSameProgress(first, completedFirst);
        assertSameProgress(second, completedSecond);
        assertSuccessfulAfter(run, completed);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldKeepSuccessfulCompletionWhenCancellationArrivesInPostLoopTeamsPhase(boolean app) {
        Run run = connect(app);
        when(teams.syncTeamsForOrganization(workspace.getId(), workspace.getAccountLogin()))
                .thenAnswer(invocation -> {
                    requestCancellation(run.connection());
                    return 0;
                });

        run.trigger().syncAllRepositories(workspace.getId());

        assertBothCompleted();
        assertThat(lastJob(run.connection()).isCancelRequested()).isTrue();
        assertSuccessful(run);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldKeepUnreportedFailureWhenCancellationWasRequestedButNoCheckpointStoppedWork(boolean app) {
        Run run = connect(app);
        when(rateLimit.isCritical(workspace.getId())).thenAnswer(invocation -> {
            requestCancellation(run.connection());
            throw new IllegalStateException("Rate budget observation failed");
        });

        run.trigger().syncAllRepositories(workspace.getId());

        assertThat(processed).isEmpty();
        assertUntouched(first);
        assertUntouched(second);
        SyncJob job = lastJob(run.connection());
        assertThat(job.isCancelRequested()).isTrue();
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.FAILED);
        assertThat(job.getErrorSummary()).contains("Rate budget observation failed");
        assertThat(status.getStatus(workspace.getId(), run.connection().getId()).lastSuccessfulSyncAt())
                .isEqualTo(run.previousSuccessfulAt());
    }

    private Run connect(boolean app) {
        ConnectionConfig config = app
                ? new ConnectionConfig.GitHubAppConfig(123L, workspace.getAccountLogin(), null, Set.of())
                : new ConnectionConfig.GitHubPatConfig(workspace.getAccountLogin(), null, Set.of());
        Connection connection = new Connection(workspace, IntegrationKind.GITHUB, "initial-group", config);
        connection.setState(IntegrationState.ACTIVE);
        connection = connectionRepository.saveAndFlush(connection);
        assertThat(syncTargets.getSyncTargetsForScope(workspace.getId())).hasSize(2);
        jobs.run(
                new SyncJobRequest(
                        workspace.getId(),
                        connection.getId(),
                        IntegrationKind.GITHUB,
                        SyncJobType.RECONCILIATION,
                        SyncJobTrigger.MANUAL,
                        null),
                handle -> {});
        Instant previousSuccessfulAt = Objects.requireNonNull(
                status.getStatus(workspace.getId(), connection.getId()).lastSuccessfulSyncAt());

        SyncSchedulerProperties properties = new SyncSchedulerProperties(
                true,
                7,
                "0 0 3 * * *",
                15,
                new SyncSchedulerProperties.BackfillProperties(false, 50, 100, 60),
                new SyncSchedulerProperties.FilterProperties(Set.of(), Set.of()));
        GitHubDataSyncService service = new GitHubDataSyncService(
                properties,
                gitProviderRepository,
                syncTargets,
                mock(OrganizationMembershipListener.class),
                repositoryRepository,
                mock(GitHubLabelSyncService.class),
                mock(GitHubMilestoneSyncService.class),
                issues,
                mock(GitHubIssueDependencySyncService.class),
                mock(GitHubIssueTypeSyncService.class),
                mock(GitHubSubIssueSyncService.class),
                pullRequests,
                teams,
                mock(GitHubOrganizationSyncService.class),
                metadata,
                mock(GitHubCollaboratorSyncService.class),
                mock(GitHubCommitBackfillService.class),
                mock(CommitAuthorEnrichmentService.class),
                mock(CommitMetadataEnrichmentService.class),
                exceptionClassifier,
                tokenProvider,
                mock(GitHubAppTokenService.class),
                rateLimit,
                mock(ActivityLedgerRepair.class),
                mock(GitHubIssueCommentSyncService.class));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("service", service);
        beans.addBean("targets", syncTargets);
        beans.addBean("connections", connectionRepository);
        beans.addBean("jobs", jobs);
        GitHubWorkspaceDataSyncTrigger trigger = new GitHubWorkspaceDataSyncTrigger(
                beans.getBeanProvider(GitHubDataSyncService.class),
                beans.getBeanProvider(SyncTargetProvider.class),
                beans.getBeanProvider(ConnectionRepository.class),
                beans.getBeanProvider(SyncJobService.class),
                beans.getBeanProvider(WorkspaceRepositoryMonitorService.class),
                new ConcurrentTaskExecutor(Runnable::run));
        return new Run(connection, trigger, previousSuccessfulAt);
    }

    private record Run(Connection connection, GitHubWorkspaceDataSyncTrigger trigger, Instant previousSuccessfulAt) {}

    private void stubIssueOutcome(Repository repository, SyncResult result) {
        doAnswer(invocation -> {
                    Long repositoryId = invocation.getArgument(1);
                    processed.add(repositoryId);
                    return repositoryId.equals(repository.getId()) ? result : SyncResult.completed(1);
                })
                .when(issues)
                .syncForRepository(any(), any(), any(), any(), any());
    }

    private Repository monitoredRepository(String name, long nativeId) {
        Repository repository = new Repository();
        repository.setNativeId(nativeId);
        repository.setProvider(ensureGitHubProvider());
        repository.setName(name.substring(name.lastIndexOf('/') + 1));
        repository.setNameWithOwner(name);
        repository.setHtmlUrl("https://github.com/" + name);
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
        assertThat(monitorRepository.saveAndFlush(monitor).getId()).isNotNull();
        return repository;
    }

    private void requestCancellation(Connection connection) {
        SyncJob active = jobRepository
                .findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(connection.getId(), SyncJobStatus.ACTIVE)
                .orElseThrow();
        jobs.requestCancel(workspace.getId(), Objects.requireNonNull(active.getId()));
    }

    private SyncJob lastJob(Connection connection) {
        return jobRepository
                .findFirstByConnection_IdAndStatusInOrderByFinishedAtDesc(connection.getId(), SyncJobStatus.TERMINAL)
                .orElseThrow();
    }

    private void assertCancelled(Run run) {
        SyncJob job = lastJob(run.connection());
        assertThat(job.isCancelRequested()).isTrue();
        assertThat(job.getErrorSummary()).isNull();
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.CANCELLED);
        assertThat(status.getStatus(workspace.getId(), run.connection().getId()).lastSuccessfulSyncAt())
                .isEqualTo(run.previousSuccessfulAt());
    }

    private void assertSuccessful(Run run) {
        SyncJob job = lastJob(run.connection());
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
        assertThat(job.getErrorSummary()).isNull();
        assertThat(status.getStatus(workspace.getId(), run.connection().getId()).lastSuccessfulSyncAt())
                .isEqualTo(job.getFinishedAt())
                .isAfter(run.previousSuccessfulAt());
    }

    private SyncJob assertWarnings(Run run) {
        SyncJob job = lastJob(run.connection());
        assertThat(job.getType()).isEqualTo(SyncJobType.INITIAL);
        assertThat(job.getTrigger()).isEqualTo(SyncJobTrigger.LIFECYCLE);
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
        assertThat(job.isCancelRequested()).isFalse();
        assertThat(job.getErrorSummary()).isNull();
        assertThat(job.getProgress())
                .containsEntry("phase", "repositories")
                .containsEntry("currentStep", "Repository refresh did not complete.")
                .containsEntry("currentRepository", first.getNameWithOwner());
        assertThat(status.getStatus(workspace.getId(), run.connection().getId()).lastSuccessfulSyncAt())
                .isEqualTo(job.getFinishedAt())
                .isAfter(run.previousSuccessfulAt());
        return job;
    }

    private void assertSuccessfulAfter(Run run, SyncJob previousJob) {
        assertSuccessful(run);
        SyncJob job = lastJob(run.connection());
        assertThat(job.getId()).isNotEqualTo(previousJob.getId());
        assertThat(job.getType()).isEqualTo(SyncJobType.INITIAL);
        assertThat(job.getTrigger()).isEqualTo(SyncJobTrigger.LIFECYCLE);
        assertThat(job.getFinishedAt()).isAfter(Objects.requireNonNull(previousJob.getFinishedAt()));
    }

    private void assertBothCompleted() {
        assertThat(processed).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertCompleted(first);
        assertCompleted(second);
    }

    private void assertCompleted(Repository repository) {
        RepositoryToMonitor monitor = monitor(repository);
        assertThat(monitor)
                .extracting(
                        RepositoryToMonitor::getRepositorySyncedAt,
                        RepositoryToMonitor::getRepositoryVisibilityConfirmedAt,
                        RepositoryToMonitor::getIssuesSyncedAt,
                        RepositoryToMonitor::getPullRequestsSyncedAt)
                .doesNotContainNull();
        assertThat(monitor.getSyncErrorSummary()).isNull();
    }

    private void assertIssuesIncomplete(Repository repository) {
        RepositoryToMonitor monitor = monitor(repository);
        assertThat(monitor)
                .extracting(
                        RepositoryToMonitor::getRepositorySyncedAt,
                        RepositoryToMonitor::getRepositoryVisibilityConfirmedAt,
                        RepositoryToMonitor::getPullRequestsSyncedAt)
                .doesNotContainNull();
        assertThat(monitor.getIssuesSyncedAt()).isNull();
        assertThat(monitor.getRecentSyncError()).isEqualTo("Issue sync: ABORTED_ERROR");
        assertThat(monitor.getHistoricalBackfillSyncError()).isNull();
        assertThat(monitor.getSyncErrorSummary()).isEqualTo("Issue sync: ABORTED_ERROR");
    }

    private void assertSameProgress(Repository repository, RepositoryToMonitor previous) {
        RepositoryToMonitor monitor = monitor(repository);
        assertThat(monitor)
                .extracting(
                        RepositoryToMonitor::getRepositorySyncedAt,
                        RepositoryToMonitor::getRepositoryVisibilityConfirmedAt,
                        RepositoryToMonitor::getIssuesSyncedAt,
                        RepositoryToMonitor::getPullRequestsSyncedAt,
                        RepositoryToMonitor::getCollaboratorsSyncedAt,
                        RepositoryToMonitor::getLabelsSyncedAt,
                        RepositoryToMonitor::getMilestonesSyncedAt)
                .containsExactly(
                        previous.getRepositorySyncedAt(),
                        previous.getRepositoryVisibilityConfirmedAt(),
                        previous.getIssuesSyncedAt(),
                        previous.getPullRequestsSyncedAt(),
                        previous.getCollaboratorsSyncedAt(),
                        previous.getLabelsSyncedAt(),
                        previous.getMilestonesSyncedAt());
        assertThat(monitor.getSyncErrorSummary()).isNull();
    }

    private void assertUntouched(Repository repository) {
        RepositoryToMonitor monitor = monitor(repository);
        assertThat(monitor)
                .extracting(
                        RepositoryToMonitor::getRepositorySyncedAt,
                        RepositoryToMonitor::getRepositoryVisibilityConfirmedAt,
                        RepositoryToMonitor::getIssuesSyncedAt,
                        RepositoryToMonitor::getPullRequestsSyncedAt)
                .containsOnlyNulls();
        assertThat(monitor.getSyncErrorSummary()).isNull();
    }

    private RepositoryToMonitor monitor(Repository repository) {
        return monitorRepository.findByWorkspaceId(workspace.getId()).stream()
                .filter(candidate -> candidate.getNameWithOwner().equals(repository.getNameWithOwner()))
                .findFirst()
                .orElseThrow();
    }
}
