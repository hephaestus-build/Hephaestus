package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncServiceHolder;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabGroupSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabSyncResult;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link GitLabWorkspaceInitializationService}.
 */
@Tag("unit")
class GitLabWorkspaceInitializationServiceTest extends BaseUnitTest {

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

    @Mock
    private GitLabRepositoryMonitors repositoryMonitors;

    private GitLabWorkspaceInitializationService initService;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        NatsConnectionProperties natsProperties = new NatsConnectionProperties(
                true, "nats://localhost:4222", null, new NatsConnectionProperties.Consumer(Duration.ofSeconds(60)));
        SyncSchedulerProperties syncProps = new SyncSchedulerProperties(
                true,
                7,
                "0 0 3 * * *",
                15,
                new SyncSchedulerProperties.BackfillProperties(false, 50, 100, 60),
                new SyncSchedulerProperties.FilterProperties(Set.of(), Set.of(), Set.of()),
                new SyncSchedulerProperties.DiscussionsProperties(false),
                new SyncSchedulerProperties.ProjectsProperties(false));

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
                new GitLabWorkspaceLinkService(workspaceRepository, organizationRepository),
                repositoryMonitors,
                monitoringExecutor);

        workspace = new Workspace();
        workspace.setAccountLogin("my-group/subgroup");
        ReflectionTestUtils.setField(workspace, "id", 1L);

        // GitLab integration metadata lives on a Connection row, not on Workspace. Each test that
        // triggers initialize() needs an active GitLab Connection + bearer token; default-configure that
        // here so the test bodies don't have to know about the Connection registry.
        lenient()
                .when(connectionService.findActiveGitLabConfig(anyLong()))
                .thenReturn(Optional.of(new ConnectionConfig.GitLabConfig(
                        "https://gitlab.com",
                        null,
                        null,
                        ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                        Set.of())));
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
                new SyncSchedulerProperties.FilterProperties(Set.of(), Set.of(), Set.of()),
                new SyncSchedulerProperties.DiscussionsProperties(false),
                new SyncSchedulerProperties.ProjectsProperties(false));
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
                new GitLabWorkspaceLinkService(workspaceRepository, organizationRepository),
                repositoryMonitors,
                monitoringExecutor);
    }

    /** Configures the executor mock to run submitted tasks synchronously. */
    private void executeSubmittedTasksSynchronously() {
        when(monitoringExecutor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        });
    }

    private Repository createRepo(String nameWithOwner) {
        Repository repo = new Repository();
        repo.setNameWithOwner(nameWithOwner);
        return repo;
    }

    /** Sets up mocks for a minimal successful discovery (no webhook, no org). */
    private void stubMinimalDiscovery(List<Repository> repos) {
        GitLabSyncResult syncResult = GitLabSyncResult.completed(repos, 1, 0, 0);
        when(gitLabWebhookServiceProvider.getIfAvailable()).thenReturn(null);
        when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(gitLabSyncServiceHolder);
        when(gitLabSyncServiceHolder.getGroupSyncService()).thenReturn(gitLabGroupSyncService);
        when(gitLabGroupSyncService.syncGroupProjects(eq(1L), eq("my-group/subgroup"), any()))
                .thenReturn(syncResult);
        when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(
                        "my-group/subgroup", IdentityProviderType.GITLAB))
                .thenReturn(Optional.empty());
        when(repositoryToMonitorRepository.findByWorkspaceId(1L)).thenReturn(List.of());
    }

    @Nested
    class InitializeGuards {

        @Test
        void shouldSkipNonGitLab() {
            // Drop the default GitLab Connection mock — this workspace has no GitLab
            // binding so initialize() must short-circuit before touching any sync service.
            when(connectionService.findActiveGitLabConfig(anyLong())).thenReturn(Optional.empty());

            initService.initialize(workspace);

            verifyNoInteractions(gitLabSyncServiceHolderProvider);
            verifyNoInteractions(gitLabWebhookServiceProvider);
        }

        @Test
        void shouldSkipGitHubApp() {
            // GitHub App workspace = no GitLab Connection at all.
            when(connectionService.findActiveGitLabConfig(anyLong())).thenReturn(Optional.empty());

            initService.initialize(workspace);

            verifyNoInteractions(gitLabSyncServiceHolderProvider);
        }

        @Test
        void shouldSkipNullToken() {
            when(connectionService.findActiveBearerToken(anyLong(), eq(IntegrationKind.GITLAB)))
                    .thenReturn(Optional.empty());

            initService.initialize(workspace);

            verifyNoInteractions(gitLabSyncServiceHolderProvider);
        }

        @Test
        void shouldSkipBlankToken() {
            when(connectionService.findActiveBearerToken(anyLong(), eq(IntegrationKind.GITLAB)))
                    .thenReturn(Optional.of(new BearerToken("   ", null)));

            initService.initialize(workspace);

            verifyNoInteractions(gitLabSyncServiceHolderProvider);
        }

        @Test
        void shouldSkipEmptyAccountLogin() {
            workspace.setAccountLogin("");

            initService.initialize(workspace);

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
        }

        @Test
        void shouldContinueWhenTokenRotationFails() {
            doThrow(new RuntimeException("rotation failed"))
                    .when(gitLabWebhookService)
                    .rotateTokenIfNeeded(workspace);
            when(gitLabWebhookService.registerWebhook(workspace)).thenReturn(WebhookSetupResult.success(99L, 42L));
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));

            initService.initialize(workspace);

            verify(gitLabWebhookService).registerWebhook(workspace);
        }

        @Test
        void shouldContinueWhenWebhookRegistrationFails() {
            when(gitLabWebhookService.registerWebhook(workspace)).thenThrow(new RuntimeException("webhook failed"));
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));

            assertThatCode(() -> initService.initialize(workspace)).doesNotThrowAnyException();
        }

        @Test
        void shouldRegisterWebhookWhenDiscoveryVerifiesAnEmptyGroup() throws Exception {
            when(gitLabWebhookService.registerWebhook(workspace)).thenReturn(WebhookSetupResult.success(99L, 42L));
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));

            initService.initialize(workspace);

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

            initService.initialize(workspace);

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

            initService.initialize(workspace);

            verify(gitLabWebhookService, never()).registerWebhook(any());
        }

        @Test
        void shouldKeepWebhookClosedWhenScopeConsumerCannotBeConfirmed() throws Exception {
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));
            doThrow(new IOException("stream gitlab not found"))
                    .when(natsConsumerService)
                    .establishScopeConsumer(1L, "gitlab");

            initService.initialize(workspace);

            verify(gitLabWebhookService, never()).registerWebhook(any());
        }

        @Test
        void shouldKeepWebhookClosedWhenThisRuntimeHasNoScopeConsumer() {
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));
            when(natsConsumerServiceProvider.getIfAvailable()).thenReturn(null);

            initService.initialize(workspace);

            verify(gitLabWebhookService, never()).registerWebhook(any());
        }

        @Test
        void shouldKeepWebhookClosedWhenNatsIsDisabled() {
            List<Repository> repos = List.of(createRepo("my-group/project-a"));
            stubDiscovery(GitLabSyncResult.completed(repos, 1, 0, 0));

            createServiceWithNatsDisabled().initialize(workspace);

            verify(repositoryMonitors).monitorAll(workspace, repos);
            verify(gitLabWebhookService, never()).registerWebhook(any());
            verifyNoInteractions(natsConsumerService);
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
                    .thenReturn(Optional.of(new ConnectionConfig.GitLabConfig(
                            "https://gitlab.com",
                            42L,
                            99L,
                            ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                            Set.of())));

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
            when(gitLabWebhookService.registerWebhook(workspace)).thenReturn(WebhookSetupResult.success(99L, 42L));
            when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(gitLabSyncServiceHolder);
            when(gitLabSyncServiceHolder.getGroupSyncService()).thenReturn(gitLabGroupSyncService);
            when(gitLabGroupSyncService.syncGroupProjects(eq(1L), eq("my-group/subgroup"), any()))
                    .thenReturn(syncResult);

            Organization organization = new Organization();
            ReflectionTestUtils.setField(organization, "id", 10L);
            organization.setLogin("my-group/subgroup");
            when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(
                            "my-group/subgroup", IdentityProviderType.GITLAB))
                    .thenReturn(Optional.of(organization));
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));

            initService.initialize(workspace);

            assertThat(workspace.getOrganization()).isEqualTo(organization);

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

            initService.initialize(workspace);

            verify(repositoryMonitors, never()).monitorAll(any(), any());
            verify(organizationRepository, never()).findByLoginIgnoreCaseAndProvider_Type(any(), any());
        }

        @Test
        void shouldSkipDiscoveryWhenSyncServiceUnavailable() {
            when(gitLabWebhookServiceProvider.getIfAvailable()).thenReturn(null);
            when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(null);

            initService.initialize(workspace);

            verify(repositoryMonitors, never()).monitorAll(any(), any());
        }

        @Test
        void shouldCompleteInitializationWhenProjectDiscoveryFails() {
            when(gitLabWebhookServiceProvider.getIfAvailable()).thenReturn(null);
            when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(gitLabSyncServiceHolder);
            when(gitLabSyncServiceHolder.getGroupSyncService()).thenReturn(gitLabGroupSyncService);
            when(gitLabGroupSyncService.syncGroupProjects(anyLong(), any(), any()))
                    .thenThrow(new RuntimeException("GraphQL timeout"));

            // Should not throw
            initService.initialize(workspace);

            verify(repositoryMonitors, never()).monitorAll(any(), any());
        }

        @Test
        void shouldSkipOrgLinkingWhenAlreadyLinked() {
            Organization existingOrg = new Organization();
            existingOrg.setLogin("my-group/subgroup");
            workspace.setOrganization(existingOrg);

            List<Repository> repos = List.of(createRepo("my-group/project-a"));
            GitLabSyncResult syncResult = GitLabSyncResult.completed(repos, 1, 0, 0);
            when(gitLabWebhookServiceProvider.getIfAvailable()).thenReturn(null);
            when(gitLabSyncServiceHolderProvider.getIfAvailable()).thenReturn(gitLabSyncServiceHolder);
            when(gitLabSyncServiceHolder.getGroupSyncService()).thenReturn(gitLabGroupSyncService);
            when(gitLabGroupSyncService.syncGroupProjects(eq(1L), eq("my-group/subgroup"), any()))
                    .thenReturn(syncResult);

            initService.initialize(workspace);

            verify(organizationRepository, never()).findByLoginIgnoreCaseAndProvider_Type(any(), any());
        }
    }

    @Nested
    class InitializeAsync {

        @Test
        void shouldSubmitToExecutor() {
            initService.initializeAsync(1L);

            verify(monitoringExecutor).submit(any(Runnable.class));
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
            workspace.setOrganization(existingOrg);

            initService.linkWorkspaceToOrganization(workspace);

            verify(organizationRepository, never()).findByLoginIgnoreCaseAndProvider_Type(any(), any());
        }

        @Test
        void shouldSkipWhenBlankLogin() {
            workspace.setAccountLogin("");

            initService.linkWorkspaceToOrganization(workspace);

            verify(organizationRepository, never()).findByLoginIgnoreCaseAndProvider_Type(any(), any());
        }

        @Test
        void shouldLinkOrganization() {
            Organization organization = new Organization();
            ReflectionTestUtils.setField(organization, "id", 10L);

            when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(
                            "my-group/subgroup", IdentityProviderType.GITLAB))
                    .thenReturn(Optional.of(organization));
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));

            initService.linkWorkspaceToOrganization(workspace);

            ArgumentCaptor<Workspace> captor = ArgumentCaptor.forClass(Workspace.class);
            verify(workspaceRepository).save(captor.capture());
            assertThat(captor.getValue().getOrganization()).isEqualTo(organization);

            // In-memory reference must be updated too, for subsequent init phases in the same call.
            assertThat(workspace.getOrganization()).isEqualTo(organization);
        }

        @Test
        void shouldNotLinkWhenNotFound() {
            when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(
                            "my-group/subgroup", IdentityProviderType.GITLAB))
                    .thenReturn(Optional.empty());

            initService.linkWorkspaceToOrganization(workspace);

            verify(workspaceRepository, never()).save(any());
        }

        @Test
        void shouldNotLinkWhenWorkspaceDeleted() {
            Organization organization = new Organization();
            when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(
                            "my-group/subgroup", IdentityProviderType.GITLAB))
                    .thenReturn(Optional.of(organization));
            when(workspaceRepository.findById(1L)).thenReturn(Optional.empty());

            initService.linkWorkspaceToOrganization(workspace);

            verify(workspaceRepository, never()).save(any());
        }
    }
}
