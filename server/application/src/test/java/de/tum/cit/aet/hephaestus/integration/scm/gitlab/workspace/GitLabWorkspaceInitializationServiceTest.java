package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent.Problem;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabGroupSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabSyncResult;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceLookup;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceProviderAccounts;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRepositoryMonitors;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRoots;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class GitLabWorkspaceInitializationServiceTest extends BaseUnitTest {

    private final GitLabProjectAdmission projectAdmission = mock(GitLabProjectAdmission.class);

    @Mock
    private WorkspaceRoots workspaces;

    @Mock
    private WorkspaceLookup workspaceLookup;

    @Mock
    private WorkspaceProviderAccounts providerAccounts;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private WorkspaceRepositoryMonitors monitors;

    @Mock
    private ObjectProvider<GitLabWebhookService> gitLabWebhookServiceProvider;

    @Mock
    private ObjectProvider<GitLabWorkspaceDataSyncTrigger> dataSyncTriggerProvider;

    @Mock
    private GitLabWorkspaceDataSyncTrigger dataSyncTrigger;

    @Mock
    private AsyncTaskExecutor monitoringExecutor;

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

    // The sync services a test sets; every other one is null. Group project discovery is set by default.
    private @Nullable GitLabGroupSyncService groupSyncService;

    private Workspace workspace;

    @BeforeEach
    void setUp() {
        lenient().when(actorSelector.connectedProviderId(anyLong())).thenReturn(Optional.of(CONNECTED_PROVIDER_ID));

        lenient().when(dataSyncTriggerProvider.getObject()).thenReturn(dataSyncTrigger);

        groupSyncService = gitLabGroupSyncService;

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
                        new ConnectionConfig.GitLabConfig("https://gitlab.com", 42L, null, Set.of(), null)));
        lenient()
                .when(connectionService.findActiveBearerToken(anyLong(), eq(IntegrationKind.GITLAB)))
                .thenReturn(Optional.of(new BearerToken("glpat-test-token", null)));
    }

    private GitLabWorkspaceInitializationService initService() {
        return new GitLabWorkspaceInitializationService(
                workspaceLookup,
                groupSyncService,
                gitLabWebhookServiceProvider,
                dataSyncTriggerProvider,
                connectionService,
                new GitLabWorkspaceLinkService(
                        workspaces,
                        workspaceLookup,
                        providerAccounts,
                        organizationRepository,
                        actorSelector,
                        mock(SourceProviderNamespaces.class),
                        monitors),
                repositoryMonitors,
                monitoringExecutor);
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

            assertThat(initService().initialize(workspace)).isEqualTo(SyncResult.completed(0));

            verifyNoInteractions(gitLabGroupSyncService, gitLabWebhookServiceProvider);
        }

        @Test
        void shouldSkipNullToken() {
            when(connectionService.findActiveBearerToken(anyLong(), eq(IntegrationKind.GITLAB)))
                    .thenReturn(Optional.empty());

            assertThat(initService().initialize(workspace)).isEqualTo(SyncResult.abortedError(0));

            verifyNoInteractions(gitLabGroupSyncService);
        }

        @Test
        void shouldSkipBlankToken() {
            when(connectionService.findActiveBearerToken(anyLong(), eq(IntegrationKind.GITLAB)))
                    .thenReturn(Optional.of(new BearerToken("   ", null)));

            assertThat(initService().initialize(workspace)).isEqualTo(SyncResult.abortedError(0));

            verifyNoInteractions(gitLabGroupSyncService);
        }

        @Test
        void shouldSkipEmptyAccountLogin() {
            workspace.setAccountLogin("");

            assertThat(initService().initialize(workspace)).isEqualTo(SyncResult.abortedError(0));

            verifyNoInteractions(gitLabGroupSyncService);
        }
    }

    /** Stubs the group project discovery to return {@code result}. */
    private void stubDiscovery(GitLabSyncResult result) {
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

            assertThat(initService().initialize(workspace)).isEqualTo(SyncResult.completedWithWarnings(0));

            verify(gitLabWebhookService).registerWebhook(workspace);
        }

        @Test
        void shouldContinueWhenWebhookRegistrationFails() {
            when(gitLabWebhookService.registerWebhook(workspace)).thenThrow(new RuntimeException("webhook failed"));
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));

            assertThat(initService().initialize(workspace)).isEqualTo(SyncResult.abortedError(0));
        }

        @Test
        void shouldRegisterWebhookWhenDiscoveryVerifiesAnEmptyGroup() throws Exception {
            when(gitLabWebhookService.registerWebhook(workspace)).thenReturn(WebhookSetupResult.success(99L, 42L));
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));

            assertThat(initService().initialize(workspace)).isEqualTo(SyncResult.completed(0));

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
            assertThat(initService().initialize(workspace)).isEqualTo(expected);

            verify(repositoryMonitors).monitorAll(workspace, List.of(found));
            verify(gitLabWebhookService, never()).registerWebhook(any());
        }

        @Test
        void shouldInspectTheTokenButNotDiscoverProjectsWhileTheCredentialIsRefused() throws Exception {
            Connection refused = mock(Connection.class);
            when(refused.getAttentionProblem()).thenReturn(Problem.CREDENTIAL_REVOKED);
            when(connectionService.findActive(1L, IntegrationKind.GITLAB)).thenReturn(Optional.of(refused));

            assertThat(initService().initialize(workspace)).isEqualTo(SyncResult.abortedError(0));

            verify(gitLabWebhookService).rotateTokenIfNeeded(workspace);
            verifyNoInteractions(gitLabGroupSyncService);
            verify(gitLabWebhookService, never()).registerWebhook(any());
        }

        @Test
        void shouldKeepWebhookClosedWhenDiscoveryFails() {
            when(gitLabGroupSyncService.syncGroupProjects(anyLong(), any(), any()))
                    .thenThrow(new RuntimeException("GraphQL timeout"));

            assertThat(initService().initialize(workspace)).isEqualTo(SyncResult.abortedError(0));

            verify(gitLabWebhookService, never()).registerWebhook(any());
        }

        @Test
        void shouldReturnIncompleteSetupWhenEnabledRegistrationReportsFailure() {
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));
            when(gitLabWebhookService.registerWebhook(workspace))
                    .thenReturn(WebhookSetupResult.failed(WebhookSetupResult.Reason.PERMISSION_DENIED, 403));

            assertThat(initService().initialize(workspace)).isEqualTo(SyncResult.abortedError(0));
        }

        @ParameterizedTest
        @EnumSource(
                value = WebhookSetupResult.Reason.class,
                names = {"ROUTING_SECRET_MISSING", "ROUTING_KEY_UNKNOWN"})
        void shouldReportTheReasonWhenRegistrationStopsBeforeAnApiCall(WebhookSetupResult.Reason reason) {
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));
            when(gitLabWebhookService.registerWebhook(workspace)).thenReturn(WebhookSetupResult.skipped(reason));
            Logger logger = (Logger) LoggerFactory.getLogger(GitLabWorkspaceInitializationService.class);
            var appender = new ListAppender<ILoggingEvent>();
            appender.start();
            logger.addAppender(appender);
            try {
                assertThat(initService().initialize(workspace)).isEqualTo(SyncResult.abortedError(0));
                assertThat(appender.list)
                        .anySatisfy(event -> assertThat(event.getFormattedMessage())
                                .contains("Webhook setup: workspaceId=1, registered=false, reason=" + reason));
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        void shouldKeepCleanSetupWhenAutomaticRegistrationIsDisabled() throws Exception {
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));
            when(gitLabWebhookService.isRegistrationEnabled()).thenReturn(false);

            assertThat(initService().initialize(workspace)).isEqualTo(SyncResult.completed(0));
            verify(gitLabWebhookService, never()).registerWebhook(any());
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
            when(workspaceLookup.find(1L)).thenReturn(Optional.of(workspace));
            stubDiscovery(GitLabSyncResult.completed(List.of(), 1, 0, 0));

            initService().initializeIfWebhookMissing(1L);

            verify(gitLabWebhookService).registerWebhook(workspace);
        }

        @Test
        void shouldLeaveRegisteredWebhookAlone() {
            when(gitLabWebhookService.isRegistrationEnabled()).thenReturn(true);
            when(connectionService.findActiveGitLabConfig(1L))
                    .thenReturn(Optional.of(
                            new ConnectionConfig.GitLabConfig("https://gitlab.com", 42L, 99L, Set.of(), null)));

            assertThat(initService().initializeIfWebhookMissing(1L)).isFalse();
            verifyNoInteractions(gitLabGroupSyncService);
        }

        @Test
        void shouldReportWebhookStillMissingWhenDiscoveryIsIncomplete() {
            when(gitLabWebhookService.isRegistrationEnabled()).thenReturn(true);
            when(workspaceLookup.find(1L)).thenReturn(Optional.of(workspace));
            stubDiscovery(GitLabSyncResult.aborted(GitLabSyncResult.Status.ABORTED_RATE_LIMIT, List.of(), 1, 0));

            assertThat(initService().initializeIfWebhookMissing(1L)).isTrue();
            verify(gitLabWebhookService, never()).registerWebhook(any());
        }

        @Test
        void shouldNotRunTheSetupAgainOnEachSyncAfterGitLabRefusedEveryHook() {
            when(gitLabWebhookService.isRegistrationEnabled()).thenReturn(true);
            when(connectionService.findActiveGitLabConfig(1L))
                    .thenReturn(Optional.of(
                            new ConnectionConfig.GitLabConfig("https://gitlab.com", 42L, null, Set.of(), null)
                                    .withWebhooksRefusedAt(Instant.parse("2026-11-15T12:00:00Z"))));

            assertThat(initService().initializeIfWebhookMissing(1L)).isFalse();
            verifyNoInteractions(gitLabGroupSyncService);
            verify(gitLabWebhookService, never()).registerWebhook(any());
        }

        @Test
        void shouldReconcileProjectHooksWithoutRunningTheSetupAgain() {
            when(gitLabWebhookService.isRegistrationEnabled()).thenReturn(true);
            when(connectionService.findActiveGitLabConfig(1L))
                    .thenReturn(Optional.of(
                            new ConnectionConfig.GitLabConfig("https://gitlab.com", 42L, null, Set.of(), null)
                                    .withProjectHook(7L, 40L, "hash", "marker")));
            when(workspaceLookup.find(1L)).thenReturn(Optional.of(workspace));
            when(gitLabWebhookService.registerWebhook(workspace)).thenReturn(WebhookSetupResult.projectHooks(42L));

            assertThat(initService().initializeIfWebhookMissing(1L)).isFalse();
            verify(gitLabWebhookService).registerWebhook(workspace);
            verifyNoInteractions(gitLabGroupSyncService);
        }

        @Test
        void shouldSkipWhenWebhookRegistrationIsDisabled() {
            when(gitLabWebhookService.isRegistrationEnabled()).thenReturn(false);

            assertThat(initService().initializeIfWebhookMissing(1L)).isFalse();
            verifyNoInteractions(gitLabGroupSyncService);
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
            when(gitLabGroupSyncService.syncGroupProjects(eq(1L), eq("my-group/subgroup"), any()))
                    .thenReturn(syncResult);

            Organization organization = new Organization();
            ReflectionTestUtils.setField(organization, "id", 10L);
            organization.setLogin("my-group/subgroup");
            when(organizationRepository.findByLoginIgnoreCaseAndProviderId("my-group/subgroup", CONNECTED_PROVIDER_ID))
                    .thenReturn(Optional.of(organization));
            when(workspaceLookup.find(1L)).thenReturn(Optional.of(workspace));

            assertThat(initService().initialize(workspace)).isEqualTo(SyncResult.completed(2));

            assertThat(workspace.getOrganizationId()).isEqualTo(organization.getId());

            verify(repositoryMonitors).monitorAll(workspace, repos);
        }

        @Test
        void shouldNotCreateMonitorsWhenSyncReturnsEmpty() {
            GitLabSyncResult emptyResult = GitLabSyncResult.completed(Collections.emptyList(), 1, 0, 0);

            when(gitLabWebhookServiceProvider.getIfAvailable()).thenReturn(null);
            when(gitLabGroupSyncService.syncGroupProjects(eq(1L), eq("my-group/subgroup"), any()))
                    .thenReturn(emptyResult);

            assertThat(initService().initialize(workspace)).isEqualTo(SyncResult.completed(0));

            verify(repositoryMonitors, never()).monitorAll(any(), any());
            verify(organizationRepository, never()).findByLoginIgnoreCaseAndProviderId(any(), any());
        }

        @Test
        void shouldSkipDiscoveryWhenSyncServiceUnavailable() {
            when(gitLabWebhookServiceProvider.getIfAvailable()).thenReturn(null);
            groupSyncService = null;

            assertThat(initService().initialize(workspace)).isEqualTo(SyncResult.abortedError(0));

            verify(repositoryMonitors, never()).monitorAll(any(), any());
        }

        @Test
        void shouldCompleteInitializationWhenProjectDiscoveryFails() {
            when(gitLabWebhookServiceProvider.getIfAvailable()).thenReturn(null);
            when(gitLabGroupSyncService.syncGroupProjects(anyLong(), any(), any()))
                    .thenThrow(new RuntimeException("GraphQL timeout"));

            assertThat(initService().initialize(workspace)).isEqualTo(SyncResult.abortedError(0));

            verify(repositoryMonitors, never()).monitorAll(any(), any());
        }

        @Test
        void shouldPropagateMonitorFailureRatherThanDowngradeItToWarnings() {
            List<Repository> repos = List.of(createRepo("my-group/project-a"));
            stubDiscovery(GitLabSyncResult.completed(repos, 1, 0, 0));
            when(repositoryMonitors.monitorAll(workspace, repos))
                    .thenThrow(new IllegalStateException("Monitor persistence failed"));

            assertThatThrownBy(() -> initService().initialize(workspace))
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
            when(gitLabGroupSyncService.syncGroupProjects(eq(1L), eq("my-group/subgroup"), any()))
                    .thenReturn(syncResult);

            initService().initialize(workspace);

            verify(organizationRepository, never()).findByLoginIgnoreCaseAndProviderId(any(), any());
        }
    }

    @Nested
    class InitializeAsync {

        @Test
        void shouldSubmitToExecutor() {
            initService().initializeAsync(1L);

            verify(monitoringExecutor).execute(any(Runnable.class));
        }

        @Test
        void shouldRunSync() {
            executeSubmittedTasksSynchronously();
            when(workspaceLookup.find(1L)).thenReturn(Optional.of(workspace));

            initService().initializeAsync(1L);

            verify(dataSyncTrigger).syncAllRepositories(1L);
        }

        @Test
        void shouldSkipWhenNotFound() {
            executeSubmittedTasksSynchronously();
            when(workspaceLookup.find(99L)).thenReturn(Optional.empty());

            initService().initializeAsync(99L);

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

            initService().linkWorkspaceToOrganization(workspace);

            verify(organizationRepository, never()).findByLoginIgnoreCaseAndProviderId(any(), any());
        }

        @Test
        void shouldSkipWhenBlankLogin() {
            workspace.setAccountLogin("");

            initService().linkWorkspaceToOrganization(workspace);

            verify(organizationRepository, never()).findByLoginIgnoreCaseAndProviderId(any(), any());
        }

        @Test
        void shouldLinkOrganization() {
            Organization organization = new Organization();
            ReflectionTestUtils.setField(organization, "id", 10L);

            when(organizationRepository.findByLoginIgnoreCaseAndProviderId("my-group/subgroup", CONNECTED_PROVIDER_ID))
                    .thenReturn(Optional.of(organization));
            when(workspaceLookup.find(1L)).thenReturn(Optional.of(workspace));

            initService().linkWorkspaceToOrganization(workspace);

            verify(workspaces).linkOrganization(1L, 10L);

            // In-memory reference must be updated too, for subsequent init phases in the same call.
            assertThat(workspace.getOrganizationId()).isEqualTo(organization.getId());
        }

        @Test
        void shouldNotLinkWhenNotFound() {
            when(organizationRepository.findByLoginIgnoreCaseAndProviderId("my-group/subgroup", CONNECTED_PROVIDER_ID))
                    .thenReturn(Optional.empty());

            initService().linkWorkspaceToOrganization(workspace);

            verify(workspaces, never()).linkOrganization(anyLong(), anyLong());
        }

        @Test
        void shouldNotLinkWhenWorkspaceDeleted() {
            Organization organization = new Organization();
            ReflectionTestUtils.setField(organization, "id", 10L);
            when(organizationRepository.findByLoginIgnoreCaseAndProviderId("my-group/subgroup", CONNECTED_PROVIDER_ID))
                    .thenReturn(Optional.of(organization));
            when(workspaceLookup.find(1L)).thenReturn(Optional.empty());

            initService().linkWorkspaceToOrganization(workspace);

            verify(workspaces, never()).linkOrganization(anyLong(), anyLong());
        }
    }
}
