package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncServiceHolder;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.GitLabIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuetype.GitLabIssueTypeSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabGroupMemberSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabGroupSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabSyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamSyncService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class GitLabWorkspaceInitializationServiceTest extends BaseUnitTest {

    private final GitLabProjectAdmission projectAdmission = mock(GitLabProjectAdmission.class);

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private IntegrationNatsConsumer natsConsumerService;

    @Mock
    private ObjectProvider<IntegrationNatsConsumer> natsConsumerServiceProvider;

    @Mock
    private SyncTargetProvider syncTargetProvider;

    @Mock
    private ObjectProvider<GitLabSyncServiceHolder> gitLabSyncServiceHolderProvider;

    @Mock
    private ObjectProvider<GitLabWebhookService> gitLabWebhookServiceProvider;

    @Mock
    private ObjectProvider<GitLabRateLimitTracker> rateLimitTrackerProvider;

    @Mock
    private ObjectProvider<GitLabWorkspaceDataSyncTrigger> dataSyncTriggerProvider;

    @Mock
    private GitLabWorkspaceDataSyncTrigger dataSyncTrigger;

    @Mock
    private AsyncTaskExecutor monitoringExecutor;

    @Mock
    private GitLabSyncServiceHolder gitLabSyncServiceHolder;

    @Mock
    private GitLabGroupSyncService gitLabGroupSyncService;

    @Mock
    private GitLabWebhookService gitLabWebhookService;

    @Mock
    private ConnectionService connectionService;

    private static final long CONNECTED_PROVIDER_ID = 100L;

    private final WorkspaceActorSelector actorSelector = mock(WorkspaceActorSelector.class);

    @Mock
    private GitLabRepositoryMonitors repositoryMonitors;

    private GitLabWorkspaceInitializationService initService;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        lenient().when(actorSelector.connectedProviderId(anyLong())).thenReturn(Optional.of(CONNECTED_PROVIDER_ID));
        NatsConnectionProperties natsProperties = new NatsConnectionProperties(
                true, "nats://localhost:4222", null, new NatsConnectionProperties.Consumer(Duration.ofSeconds(60)));
        SyncSchedulerProperties syncProps = new SyncSchedulerProperties(
                true,
                7,
                "0 0 3 * * *",
                15,
                new SyncSchedulerProperties.BackfillProperties(false, 50, 100, 60),
                new SyncSchedulerProperties.FilterProperties(Set.of(), Set.of()));

        lenient().when(natsConsumerServiceProvider.getIfAvailable()).thenReturn(natsConsumerService);
        lenient().when(dataSyncTriggerProvider.getObject()).thenReturn(dataSyncTrigger);

        initService = new GitLabWorkspaceInitializationService(
                workspaceRepository,
                organizationRepository,
                repositoryToMonitorRepository,
                repositoryRepository,
                natsProperties,
                syncProps,
                natsConsumerServiceProvider,
                syncTargetProvider,
                gitLabSyncServiceHolderProvider,
                gitLabWebhookServiceProvider,
                rateLimitTrackerProvider,
                dataSyncTriggerProvider,
                connectionService,
                new GitLabWorkspaceLinkService(
                        workspaceRepository,
                        organizationRepository,
                        actorSelector,
                        mock(IdentityProviderRepository.class),
                        repositoryToMonitorRepository),
                repositoryMonitors,
                actorSelector,
                monitoringExecutor,
                projectAdmission);

        workspace = new Workspace();
        workspace.setAccountLogin("my-group/subgroup");
        ReflectionTestUtils.setField(workspace, "id", 1L);

        // GitLab integration metadata lives on a Connection row, not on Workspace. Each test that
        // triggers initialize() needs an active GitLab Connection + bearer token; default-configure
        // that
        // here so the test bodies don't have to know about the Connection registry.
        lenient()
                .when(connectionService.findActiveGitLabConfig(anyLong()))
                .thenReturn(Optional.of(
                        new ConnectionConfig.GitLabConfig("https://gitlab.com", null, null, Set.of(), null)));
        lenient()
                .when(connectionService.findActiveBearerToken(anyLong(), eq(IntegrationKind.GITLAB)))
                .thenReturn(Optional.of(new BearerToken("glpat-test-token", null)));
    }

    /** Creates a service instance with NATS disabled for testing skip behavior. */
    private GitLabWorkspaceInitializationService createServiceWithNatsDisabled() {
        NatsConnectionProperties disabledNats = new NatsConnectionProperties(
                false, "nats://localhost:4222", null, new NatsConnectionProperties.Consumer(Duration.ofSeconds(60)));
        SyncSchedulerProperties syncProps = new SyncSchedulerProperties(
                true,
                7,
                "0 0 3 * * *",
                15,
                new SyncSchedulerProperties.BackfillProperties(false, 50, 100, 60),
                new SyncSchedulerProperties.FilterProperties(Set.of(), Set.of()));
        return new GitLabWorkspaceInitializationService(
                workspaceRepository,
                organizationRepository,
                repositoryToMonitorRepository,
                repositoryRepository,
                disabledNats,
                syncProps,
                natsConsumerServiceProvider,
                syncTargetProvider,
                gitLabSyncServiceHolderProvider,
                gitLabWebhookServiceProvider,
                rateLimitTrackerProvider,
                dataSyncTriggerProvider,
                connectionService,
                new GitLabWorkspaceLinkService(
                        workspaceRepository,
                        organizationRepository,
                        actorSelector,
                        mock(IdentityProviderRepository.class),
                        repositoryToMonitorRepository),
                repositoryMonitors,
                actorSelector,
                monitoringExecutor,
                projectAdmission);
    }

    /** Configures the executor mock to run submitted tasks synchronously. */
    private void executeSubmittedTasksSynchronously() {
        doAnswer(invocation -> {
                    Runnable task = invocation.getArgument(0);
                    task.run();
                    return null;
                })
                .when(monitoringExecutor)
                .execute(any(Runnable.class));
    }

    private Repository createRepo(String nameWithOwner) {
        Repository repo = new Repository();
        repo.setId(nameWithOwner.endsWith("project-a") ? 10L : 20L);
        repo.setNameWithOwner(nameWithOwner);
        GitLabAdmissionTestFixtures.permit(projectAdmission, 1L, repo, repo.getId());
        return repo;
    }

    @Nested
    class InitializeGuards {

        @Test
        void shouldSkipWhenTheWorkspaceHasNoGitLabConnection() {
            when(connectionService.findActiveGitLabConfig(anyLong())).thenReturn(Optional.empty());

            assertThat(initService.initialize(workspace)).isEqualTo(SyncResult.completed(0));

            verifyNoInteractions(gitLabSyncServiceHolderProvider, gitLabWebhookServiceProvider);
        }

        @Test
        void shouldSkipNullToken() {
            when(connectionService.findActiveBearerToken(anyLong(), eq(IntegrationKind.GITLAB)))
                    .thenReturn(Optional.empty());

            assertThat(initService.initialize(workspace)).isEqualTo(SyncResult.abortedError(0));

            verifyNoInteractions(gitLabSyncServiceHolderProvider);
        }

        @Test
        void shouldSkipBlankToken() {
            when(connectionService.findActiveBearerToken(anyLong(), eq(IntegrationKind.GITLAB)))
                    .thenReturn(Optional.of(new BearerToken("   ", null)));

            assertThat(initService.initialize(workspace)).isEqualTo(SyncResult.abortedError(0));

            verifyNoInteractions(gitLabSyncServiceHolderProvider);
        }

        @Test
        void shouldSkipEmptyAccountLogin() {
            workspace.setAccountLogin("");

            assertThat(initService.initialize(workspace)).isEqualTo(SyncResult.abortedError(0));

            verifyNoInteractions(gitLabSyncServiceHolderProvider);
        }
    }

    /** Stubs the group project discovery to return {@code result}. */
    private void stubDiscovery(GitLabSyncResult result) {
        when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(gitLabSyncServiceHolder);
        when(gitLabSyncServiceHolder.getGroupSyncService()).thenReturn(gitLabGroupSyncService);
        when(gitLabGroupSyncService.syncGroupProjects(eq(1L), eq("my-group/subgroup"), any()))
                .thenReturn(result);
    }

    @Nested
    class InitializeWebhook {

        @BeforeEach
        void provideWebhookService() {
            when(gitLabWebhookServiceProvider.getIfAvailable()).thenReturn(gitLabWebhookService);
            lenient().when(gitLabWebhookService.isRegistrationEnabled()).thenReturn(true);
        }

        @Test
        void shouldContinueWhenTokenRotationFails() {
            doThrow(new RuntimeException("rotation failed"))
                    .when(gitLabWebhookService)
                    .rotateTokenIfNeeded(workspace);
            when(gitLabWebhookService.registerWebhook(workspace)).thenReturn(WebhookSetupResult.success(99L, 42L));
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));

            assertThat(initService.initialize(workspace)).isEqualTo(SyncResult.completedWithWarnings(0));

            verify(gitLabWebhookService).registerWebhook(workspace);
        }

        @Test
        void shouldContinueWhenWebhookRegistrationFails() {
            when(gitLabWebhookService.registerWebhook(workspace)).thenThrow(new RuntimeException("webhook failed"));
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));

            assertThat(initService.initialize(workspace)).isEqualTo(SyncResult.abortedError(0));
        }

        @Test
        void shouldRegisterWebhookWhenDiscoveryVerifiesAnEmptyGroup() throws Exception {
            when(gitLabWebhookService.registerWebhook(workspace)).thenReturn(WebhookSetupResult.success(99L, 42L));
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));

            assertThat(initService.initialize(workspace)).isEqualTo(SyncResult.completed(0));

            verify(natsConsumerService).establishScopeConsumer(1L, "gitlab");
            verify(gitLabWebhookService).registerWebhook(workspace);
        }

        @ParameterizedTest
        @EnumSource(
                value = GitLabSyncResult.Status.class,
                names = {"COMPLETED_WITH_ERRORS", "ABORTED_RATE_LIMIT", "ABORTED_ERROR"})
        void shouldRouteFoundRepositoriesButKeepWebhookClosedWhenDiscoveryIsIncomplete(GitLabSyncResult.Status status)
                throws Exception {
            Repository found = createRepo("my-group/project-a");
            stubDiscovery(new GitLabSyncResult(status, List.of(found), 1, 1, 0, 0));

            SyncResult expected = switch (status) {
                case COMPLETED_WITH_ERRORS -> SyncResult.completedWithWarnings(1);
                case ABORTED_RATE_LIMIT -> SyncResult.abortedRateLimit(1);
                case ABORTED_ERROR -> SyncResult.abortedError(1);
                case COMPLETED -> SyncResult.completed(1);
            };
            assertThat(initService.initialize(workspace)).isEqualTo(expected);

            verify(repositoryMonitors).monitorAll(workspace, List.of(found));
            verify(natsConsumerService).establishScopeConsumer(1L, "gitlab");
            verify(gitLabWebhookService, never()).registerWebhook(any());
        }

        @Test
        void shouldKeepWebhookClosedWhenDiscoveryFails() {
            when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(gitLabSyncServiceHolder);
            when(gitLabSyncServiceHolder.getGroupSyncService()).thenReturn(gitLabGroupSyncService);
            when(gitLabGroupSyncService.syncGroupProjects(anyLong(), any(), any()))
                    .thenThrow(new RuntimeException("GraphQL timeout"));

            assertThat(initService.initialize(workspace)).isEqualTo(SyncResult.abortedError(0));

            verify(gitLabWebhookService, never()).registerWebhook(any());
        }

        @Test
        void shouldKeepWebhookClosedWhenScopeConsumerCannotBeConfirmed() throws Exception {
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));
            doThrow(new IOException("stream gitlab not found"))
                    .when(natsConsumerService)
                    .establishScopeConsumer(1L, "gitlab");

            assertThat(initService.initialize(workspace)).isEqualTo(SyncResult.abortedError(0));

            verify(gitLabWebhookService, never()).registerWebhook(any());
        }

        @Test
        void shouldKeepWebhookClosedWhenThisRuntimeHasNoScopeConsumer() {
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));
            when(natsConsumerServiceProvider.getIfAvailable()).thenReturn(null);

            assertThat(initService.initialize(workspace)).isEqualTo(SyncResult.abortedError(0));

            verify(gitLabWebhookService, never()).registerWebhook(any());
        }

        @Test
        void shouldKeepWebhookClosedWhenNatsIsDisabled() {
            List<Repository> repos = List.of(createRepo("my-group/project-a"));
            stubDiscovery(GitLabSyncResult.completed(repos, 1, 0, 0));

            assertThat(createServiceWithNatsDisabled().initialize(workspace)).isEqualTo(SyncResult.completed(1));

            verify(repositoryMonitors).monitorAll(workspace, repos);
            verify(gitLabWebhookService, never()).registerWebhook(any());
            verifyNoInteractions(natsConsumerService);
        }

        @Test
        void shouldReturnIncompleteSetupWhenEnabledRegistrationReportsFailure() {
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));
            when(gitLabWebhookService.registerWebhook(workspace))
                    .thenReturn(WebhookSetupResult.failed("GitLab refused"));

            assertThat(initService.initialize(workspace)).isEqualTo(SyncResult.abortedError(0));
        }

        @Test
        void shouldKeepCleanSetupWhenAutomaticRegistrationIsDisabled() throws Exception {
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));
            when(gitLabWebhookService.isRegistrationEnabled()).thenReturn(false);

            assertThat(initService.initialize(workspace)).isEqualTo(SyncResult.completed(0));
            verify(natsConsumerService).establishScopeConsumer(1L, "gitlab");
            verify(gitLabWebhookService, never()).registerWebhook(any());
        }

        @Test
        void shouldRequireConsumerConfirmationWhenAutomaticRegistrationIsDisabled() throws Exception {
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));
            lenient().when(gitLabWebhookService.isRegistrationEnabled()).thenReturn(false);
            doThrow(new IOException("consumer unavailable"))
                    .when(natsConsumerService)
                    .establishScopeConsumer(1L, "gitlab");

            assertThat(initService.initialize(workspace)).isEqualTo(SyncResult.abortedError(0));
        }
    }

    @Nested
    class InitializeIfWebhookMissing {

        @BeforeEach
        void provideWebhookService() {
            when(gitLabWebhookServiceProvider.getIfAvailable()).thenReturn(gitLabWebhookService);
        }

        @Test
        void shouldRegisterWebhookThatInitializationLeftClosed() {
            when(gitLabWebhookService.isRegistrationEnabled()).thenReturn(true);
            when(gitLabWebhookService.registerWebhook(workspace)).thenReturn(WebhookSetupResult.success(99L, 42L));
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));

            initService.initializeIfWebhookMissing(1L);

            verify(gitLabWebhookService).registerWebhook(workspace);
        }

        @Test
        void shouldLeaveRegisteredWebhookAlone() {
            when(gitLabWebhookService.isRegistrationEnabled()).thenReturn(true);
            when(connectionService.findActiveGitLabConfig(1L))
                    .thenReturn(Optional.of(
                            new ConnectionConfig.GitLabConfig("https://gitlab.com", 42L, 99L, Set.of(), null)));

            assertThat(initService.initializeIfWebhookMissing(1L)).isFalse();
            verifyNoInteractions(gitLabSyncServiceHolderProvider);
        }

        @Test
        void shouldReportWebhookStillMissingWhenDiscoveryIsIncomplete() {
            when(gitLabWebhookService.isRegistrationEnabled()).thenReturn(true);
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));
            stubDiscovery(GitLabSyncResult.aborted(GitLabSyncResult.Status.ABORTED_RATE_LIMIT, List.of(), 1, 0));

            assertThat(initService.initializeIfWebhookMissing(1L)).isTrue();
            verify(gitLabWebhookService, never()).registerWebhook(any());
        }

        @Test
        void shouldNotReportWebhookMissingWhenNatsIsDisabled() {
            // Registration is otherwise enabled; without NATS it must not even be consulted.
            lenient().when(gitLabWebhookService.isRegistrationEnabled()).thenReturn(true);

            assertThat(createServiceWithNatsDisabled().initializeIfWebhookMissing(1L))
                    .isFalse();
            verifyNoInteractions(gitLabSyncServiceHolderProvider);
        }

        @Test
        void shouldSkipWhenWebhookRegistrationIsDisabled() {
            when(gitLabWebhookService.isRegistrationEnabled()).thenReturn(false);

            assertThat(initService.initializeIfWebhookMissing(1L)).isFalse();
            verifyNoInteractions(gitLabSyncServiceHolderProvider);
        }
    }

    @Nested
    class InitializeDiscovery {

        @Test
        void shouldDiscoverAndCreateMonitors() throws Exception {
            List<Repository> repos = List.of(createRepo("my-group/project-a"), createRepo("my-group/project-b"));
            GitLabSyncResult syncResult = GitLabSyncResult.completed(repos, 1, 0, 0);

            when(gitLabWebhookServiceProvider.getIfAvailable()).thenReturn(gitLabWebhookService);
            when(gitLabWebhookService.isRegistrationEnabled()).thenReturn(true);
            when(gitLabWebhookService.registerWebhook(workspace)).thenReturn(WebhookSetupResult.success(99L, 42L));
            when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(gitLabSyncServiceHolder);
            when(gitLabSyncServiceHolder.getGroupSyncService()).thenReturn(gitLabGroupSyncService);
            when(gitLabGroupSyncService.syncGroupProjects(eq(1L), eq("my-group/subgroup"), any()))
                    .thenReturn(syncResult);

            Organization organization = new Organization();
            ReflectionTestUtils.setField(organization, "id", 10L);
            organization.setLogin("my-group/subgroup");
            when(organizationRepository.findByLoginIgnoreCaseAndProviderId("my-group/subgroup", CONNECTED_PROVIDER_ID))
                    .thenReturn(Optional.of(organization));
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));

            assertThat(initService.initialize(workspace)).isEqualTo(SyncResult.completed(2));

            assertThat(workspace.getOrganizationId()).isEqualTo(organization.getId());

            verify(repositoryMonitors).monitorAll(workspace, repos);

            verify(natsConsumerService).establishScopeConsumer(1L, "gitlab");
        }

        @Test
        void shouldNotCreateMonitorsWhenSyncReturnsEmpty() {
            GitLabSyncResult emptyResult = GitLabSyncResult.completed(Collections.emptyList(), 1, 0, 0);

            when(gitLabWebhookServiceProvider.getIfAvailable()).thenReturn(null);
            when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(gitLabSyncServiceHolder);
            when(gitLabSyncServiceHolder.getGroupSyncService()).thenReturn(gitLabGroupSyncService);
            when(gitLabGroupSyncService.syncGroupProjects(eq(1L), eq("my-group/subgroup"), any()))
                    .thenReturn(emptyResult);

            assertThat(initService.initialize(workspace)).isEqualTo(SyncResult.completed(0));

            verify(repositoryMonitors, never()).monitorAll(any(), any());
            verify(organizationRepository, never()).findByLoginIgnoreCaseAndProviderId(any(), any());
        }

        @Test
        void shouldSkipDiscoveryWhenSyncServiceUnavailable() {
            when(gitLabWebhookServiceProvider.getIfAvailable()).thenReturn(null);
            when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(null);

            assertThat(initService.initialize(workspace)).isEqualTo(SyncResult.abortedError(0));

            verify(repositoryMonitors, never()).monitorAll(any(), any());
        }

        @Test
        void shouldCompleteInitializationWhenProjectDiscoveryFails() {
            when(gitLabWebhookServiceProvider.getIfAvailable()).thenReturn(null);
            when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(gitLabSyncServiceHolder);
            when(gitLabSyncServiceHolder.getGroupSyncService()).thenReturn(gitLabGroupSyncService);
            when(gitLabGroupSyncService.syncGroupProjects(anyLong(), any(), any()))
                    .thenThrow(new RuntimeException("GraphQL timeout"));

            assertThat(initService.initialize(workspace)).isEqualTo(SyncResult.abortedError(0));

            verify(repositoryMonitors, never()).monitorAll(any(), any());
        }

        @Test
        void shouldPropagateMonitorFailureRatherThanDowngradeItToWarnings() {
            List<Repository> repos = List.of(createRepo("my-group/project-a"));
            stubDiscovery(GitLabSyncResult.completed(repos, 1, 0, 0));
            when(repositoryMonitors.monitorAll(workspace, repos))
                    .thenThrow(new IllegalStateException("Monitor persistence failed"));

            assertThatThrownBy(() -> initService.initialize(workspace))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Monitor persistence failed");
        }

        @Test
        void shouldSkipOrgLinkingWhenAlreadyLinked() {
            Organization existingOrg = new Organization();
            existingOrg.setId(5L);
            existingOrg.setLogin("my-group/subgroup");
            workspace.setOrganizationId(existingOrg.getId());

            List<Repository> repos = List.of(createRepo("my-group/project-a"));
            GitLabSyncResult syncResult = GitLabSyncResult.completed(repos, 1, 0, 0);
            when(gitLabWebhookServiceProvider.getIfAvailable()).thenReturn(null);
            when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(gitLabSyncServiceHolder);
            when(gitLabSyncServiceHolder.getGroupSyncService()).thenReturn(gitLabGroupSyncService);
            when(gitLabGroupSyncService.syncGroupProjects(eq(1L), eq("my-group/subgroup"), any()))
                    .thenReturn(syncResult);

            initService.initialize(workspace);

            verify(organizationRepository, never()).findByLoginIgnoreCaseAndProviderId(any(), any());
        }
    }

    @Nested
    class InitializeAsync {

        @Test
        void shouldSubmitToExecutor() {
            initService.initializeAsync(1L);

            verify(monitoringExecutor).execute(any(Runnable.class));
        }

        @Test
        void shouldRunSync() {
            executeSubmittedTasksSynchronously();
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));

            initService.initializeAsync(1L);

            verify(dataSyncTrigger).syncAllRepositories(1L);
        }

        @Test
        void shouldSkipWhenNotFound() {
            executeSubmittedTasksSynchronously();
            when(workspaceRepository.findById(99L)).thenReturn(Optional.empty());

            initService.initializeAsync(99L);

            verifyNoInteractions(dataSyncTrigger);
        }
    }

    @Nested
    class LinkWorkspaceToOrganization {

        @Test
        void shouldSkipWhenAlreadyLinked() {
            Organization existingOrg = new Organization();
            existingOrg.setId(5L);
            workspace.setOrganizationId(existingOrg.getId());

            initService.linkWorkspaceToOrganization(workspace);

            verify(organizationRepository, never()).findByLoginIgnoreCaseAndProviderId(any(), any());
        }

        @Test
        void shouldSkipWhenBlankLogin() {
            workspace.setAccountLogin("");

            initService.linkWorkspaceToOrganization(workspace);

            verify(organizationRepository, never()).findByLoginIgnoreCaseAndProviderId(any(), any());
        }

        @Test
        void shouldLinkOrganization() {
            Organization organization = new Organization();
            ReflectionTestUtils.setField(organization, "id", 10L);

            when(organizationRepository.findByLoginIgnoreCaseAndProviderId("my-group/subgroup", CONNECTED_PROVIDER_ID))
                    .thenReturn(Optional.of(organization));
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));

            initService.linkWorkspaceToOrganization(workspace);

            ArgumentCaptor<Workspace> captor = ArgumentCaptor.forClass(Workspace.class);
            verify(workspaceRepository).save(captor.capture());
            assertThat(captor.getValue().getOrganizationId()).isEqualTo(organization.getId());

            // In-memory reference must be updated too, for subsequent init phases in the same call.
            assertThat(workspace.getOrganizationId()).isEqualTo(organization.getId());
        }

        @Test
        void shouldNotLinkWhenNotFound() {
            when(organizationRepository.findByLoginIgnoreCaseAndProviderId("my-group/subgroup", CONNECTED_PROVIDER_ID))
                    .thenReturn(Optional.empty());

            initService.linkWorkspaceToOrganization(workspace);

            verify(workspaceRepository, never()).save(any());
        }

        @Test
        void shouldNotLinkWhenWorkspaceDeleted() {
            Organization organization = new Organization();
            when(organizationRepository.findByLoginIgnoreCaseAndProviderId("my-group/subgroup", CONNECTED_PROVIDER_ID))
                    .thenReturn(Optional.of(organization));
            when(workspaceRepository.findById(1L)).thenReturn(Optional.empty());

            initService.linkWorkspaceToOrganization(workspace);

            verify(workspaceRepository, never()).save(any());
        }
    }

    @Nested
    class SyncFullData {

        @Test
        void shouldNotAdvanceRepositoryCooldownAfterAuthorityIsWithdrawnAtTheIssueWatermark() {
            var issueSync = mock(GitLabIssueSyncService.class);
            when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(gitLabSyncServiceHolder);
            when(gitLabSyncServiceHolder.getIssueSyncService()).thenReturn(issueSync);
            Repository stopped = new Repository();
            stopped.setId(10L);
            stopped.setNameWithOwner("my-group/subgroup/project-a");
            Repository sibling = new Repository();
            sibling.setId(20L);
            sibling.setNameWithOwner("my-group/subgroup/project-b");
            var originalRead = GitLabAdmissionTestFixtures.permit(projectAdmission, 1L, stopped, 77L);
            GitLabAdmissionTestFixtures.permit(projectAdmission, 1L, sibling, 78L);
            var originalMonitor = new RepositoryToMonitor();
            originalMonitor.setId(77L);
            originalMonitor.setNameWithOwner(stopped.getNameWithOwner());
            var siblingMonitor = new RepositoryToMonitor();
            siblingMonitor.setId(78L);
            siblingMonitor.setNameWithOwner(sibling.getNameWithOwner());
            when(repositoryRepository.findAllByWorkspaceMonitors(1L)).thenReturn(List.of(stopped, sibling));
            when(repositoryToMonitorRepository.findByWorkspaceId(1L))
                    .thenReturn(List.of(originalMonitor, siblingMonitor));
            when(issueSync.syncIssues(eq(1L), any(), any(), any())).thenReturn(SyncResult.completed(1));
            doAnswer(invocation -> {
                        doReturn(false).when(projectAdmission).hold(originalRead);
                        return null;
                    })
                    .when(syncTargetProvider)
                    .updateSyncTimestamp(eq(77L), eq(SyncType.ISSUES), any());

            assertThat(initService.syncFullData(workspace)).isEqualTo(SyncResult.completed(2));

            verify(repositoryRepository).updateLastSyncAt(eq(20L), any());
            verify(syncTargetProvider).updateSyncTimestamp(eq(78L), eq(SyncType.FULL_REPOSITORY), any());
            verify(repositoryRepository, never()).updateLastSyncAt(eq(10L), any());
            verify(syncTargetProvider, never()).updateSyncTimestamp(eq(77L), eq(SyncType.FULL_REPOSITORY), any());
        }

        @Test
        void shouldSyncTheRosterOfTheGroupOnTheConnectedInstanceWhenAnotherHasTheSamePath() {
            Organization onConnectedInstance = new Organization();
            GitLabGroupMemberSyncService memberSync = mock(GitLabGroupMemberSyncService.class);
            when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(gitLabSyncServiceHolder);
            when(gitLabSyncServiceHolder.getGroupMemberSyncService()).thenReturn(memberSync);
            when(organizationRepository.findByLoginIgnoreCaseAndProviderId("my-group/subgroup", CONNECTED_PROVIDER_ID))
                    .thenReturn(Optional.of(onConnectedInstance));

            initService.syncFullData(workspace, () -> false);

            verify(memberSync).syncGroupMemberships(1L, "my-group/subgroup", onConnectedInstance);
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 0, 2})
        void shouldReportNativeRosterOutcomeWhenMembershipSyncReturns(int members) {
            Organization organization = new Organization();
            var memberSync = mock(GitLabGroupMemberSyncService.class);
            when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(gitLabSyncServiceHolder);
            when(gitLabSyncServiceHolder.getGroupMemberSyncService()).thenReturn(memberSync);
            when(organizationRepository.findByLoginIgnoreCaseAndProviderId("my-group/subgroup", CONNECTED_PROVIDER_ID))
                    .thenReturn(Optional.of(organization));
            when(memberSync.syncGroupMemberships(1L, "my-group/subgroup", organization))
                    .thenReturn(members);

            assertThat(initService.syncFullData(workspace))
                    .isEqualTo(members < 0 ? SyncResult.abortedError(0) : SyncResult.completed(members));
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void shouldRetainTeamCountAndReportCompletenessWhenTeamListingReturns(boolean complete) {
            var teams = mock(GitLabTeamSyncService.class);
            when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(gitLabSyncServiceHolder);
            when(gitLabSyncServiceHolder.getTeamSyncService()).thenReturn(teams);
            when(teams.syncTeamsForGroup(1L, "my-group/subgroup"))
                    .thenReturn(new GitLabTeamSyncService.Result(2, complete));

            assertThat(initService.syncFullData(workspace))
                    .isEqualTo(complete ? SyncResult.completed(2) : SyncResult.abortedError(2));
        }

        @Test
        void shouldKeepSuccessfulSiblingCountWhenAnIssueTypeExceptionIsCaught() {
            var issueTypes = mock(GitLabIssueTypeSyncService.class);
            var teams = mock(GitLabTeamSyncService.class);
            when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(gitLabSyncServiceHolder);
            when(gitLabSyncServiceHolder.getIssueTypeSyncService()).thenReturn(issueTypes);
            when(gitLabSyncServiceHolder.getTeamSyncService()).thenReturn(teams);
            when(issueTypes.syncIssueTypesForGroup(1L, "my-group/subgroup"))
                    .thenThrow(new IllegalStateException("Issue types unavailable"));
            when(teams.syncTeamsForGroup(1L, "my-group/subgroup"))
                    .thenReturn(new GitLabTeamSyncService.Result(2, true));

            assertThat(initService.syncFullData(workspace)).isEqualTo(SyncResult.abortedError(2));
        }

        @Test
        void shouldKeepCleanZeroWhenIssueTypesAndTeamsReportNoItems() {
            var issueTypes = mock(GitLabIssueTypeSyncService.class);
            var teams = mock(GitLabTeamSyncService.class);
            when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(gitLabSyncServiceHolder);
            when(gitLabSyncServiceHolder.getIssueTypeSyncService()).thenReturn(issueTypes);
            when(gitLabSyncServiceHolder.getTeamSyncService()).thenReturn(teams);
            when(issueTypes.syncIssueTypesForGroup(1L, "my-group/subgroup")).thenReturn(0);
            when(teams.syncTeamsForGroup(1L, "my-group/subgroup"))
                    .thenReturn(new GitLabTeamSyncService.Result(0, true));

            assertThat(initService.syncFullData(workspace)).isEqualTo(SyncResult.completed(0));
        }
    }
}
