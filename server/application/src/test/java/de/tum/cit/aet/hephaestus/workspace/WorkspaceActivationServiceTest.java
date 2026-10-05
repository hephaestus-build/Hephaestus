package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialUnreadableException;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceDataSyncTrigger;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

class WorkspaceActivationServiceTest extends BaseUnitTest {

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private ObjectProvider<IntegrationNatsConsumer> natsConsumerService;

    @Mock
    private IntegrationNatsConsumer natsConsumer;

    @Mock
    private WorkspaceScopeFilter workspaceScopeFilter;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private WorkspaceDataSyncTrigger gitHubTrigger;

    @Mock
    private WorkspaceDataSyncTrigger gitLabTrigger;

    private WorkspaceActivationService service;

    @BeforeEach
    void setUp() {
        when(gitHubTrigger.kind()).thenReturn(IntegrationKind.GITHUB);
        when(gitLabTrigger.kind()).thenReturn(IntegrationKind.GITLAB);
        when(workspaceScopeFilter.isWorkspaceAllowed(any(Workspace.class))).thenReturn(true);
        doAnswer(inv -> {
                    Consumer<IntegrationNatsConsumer> c = inv.getArgument(0);
                    c.accept(natsConsumer);
                    return null;
                })
                .when(natsConsumerService)
                .ifAvailable(any());
        // Runs each activation on the calling thread, so the assertions see its effects.
        service = activationService(true, new TaskExecutorAdapter(Runnable::run));
    }

    private WorkspaceActivationService activationService(boolean fullStartupSync, AsyncTaskExecutor executor) {
        SyncSchedulerProperties syncProps = new SyncSchedulerProperties(
                fullStartupSync,
                7,
                "0 0 3 * * *",
                15,
                new SyncSchedulerProperties.BackfillProperties(false, 50, 100, 60),
                new SyncSchedulerProperties.FilterProperties(Set.of(), Set.of(), Set.of()),
                new SyncSchedulerProperties.DiscussionsProperties(false),
                new SyncSchedulerProperties.ProjectsProperties(false));
        return new WorkspaceActivationService(
                new NatsConnectionProperties(true, "nats://localhost:4222", null, null),
                syncProps,
                workspaceRepository,
                natsConsumerService,
                workspaceScopeFilter,
                connectionService,
                List.of(gitHubTrigger, gitLabTrigger),
                executor);
    }

    private static Workspace workspace(long id) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setAccountLogin("org-" + id);
        return workspace;
    }

    @Test
    void shouldActivateHealthyWorkspacesWhenAnotherWorkspaceCredentialIsUnreadable() {
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace(5), workspace(9), workspace(12)));
        when(connectionService.findActiveProviderKind(5)).thenReturn(Optional.of(IntegrationKind.GITLAB));
        when(connectionService.findActiveBearerToken(5, IntegrationKind.GITLAB))
                .thenThrow(new CredentialUnreadableException(50, IntegrationKind.GITLAB, new IllegalStateException()));
        when(connectionService.findActiveProviderKind(9)).thenReturn(Optional.of(IntegrationKind.GITLAB));
        when(connectionService.findActiveBearerToken(9, IntegrationKind.GITLAB))
                .thenReturn(Optional.of(new BearerToken("glpat-healthy", null)));
        // A GitHub App connection has no stored token to read.
        when(connectionService.findActiveProviderKind(12)).thenReturn(Optional.of(IntegrationKind.GITHUB));
        when(connectionService.findActiveGitHubPatConfig(12)).thenReturn(Optional.empty());

        service.activateAllWorkspaces();

        verify(natsConsumer).startConsumingScope(9L);
        verify(natsConsumer).startConsumingScope(12L);
        verify(natsConsumer, never()).startConsumingScope(5L);
        verify(gitLabTrigger).syncOnStartup(9, true);
        verify(gitHubTrigger).syncOnStartup(12, true);
        verify(gitLabTrigger, never()).syncOnStartup(eq(5L), anyBoolean());
    }

    @Test
    void shouldSkipPatWorkspaceWithoutTokenWhenGitHubAppWorkspaceActivates() {
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace(7), workspace(12)));
        when(connectionService.findActiveProviderKind(7)).thenReturn(Optional.of(IntegrationKind.GITHUB));
        when(connectionService.findActiveGitHubPatConfig(7))
                .thenReturn(Optional.of(new ConnectionConfig.GitHubPatConfig(null, null, Set.of())));
        when(connectionService.findActiveBearerToken(7, IntegrationKind.GITHUB)).thenReturn(Optional.empty());
        when(connectionService.findActiveProviderKind(12)).thenReturn(Optional.of(IntegrationKind.GITHUB));
        when(connectionService.findActiveGitHubPatConfig(12)).thenReturn(Optional.empty());

        service.activateAllWorkspaces();

        verify(natsConsumer).startConsumingScope(12L);
        verify(natsConsumer, never()).startConsumingScope(7L);
        verify(gitHubTrigger).syncOnStartup(12, true);
        verify(gitHubTrigger, never()).syncOnStartup(eq(7L), anyBoolean());
        verify(connectionService, never()).findActiveBearerToken(12, IntegrationKind.GITHUB);
    }

    @Test
    void shouldRunStartupSyncWithoutFullSyncWhenFullStartupSyncIsDisabled() {
        service = activationService(false, new TaskExecutorAdapter(Runnable::run));
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace(12)));
        when(connectionService.findActiveProviderKind(12)).thenReturn(Optional.of(IntegrationKind.GITHUB));
        when(connectionService.findActiveGitHubPatConfig(12)).thenReturn(Optional.empty());

        service.activateAllWorkspaces();

        verify(natsConsumer).startConsumingScope(12L);
        verify(gitHubTrigger).syncOnStartup(12, false);
        verify(gitHubTrigger, never()).syncAllRepositories(anyLong());
    }

    @Test
    void shouldStartGitLabWorkspaceWhenGitHubStartupSyncIsStillRunning() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gitHubStarted = new CountDownLatch(1);
        CountDownLatch releaseGitHub = new CountDownLatch(1);
        try {
            service = activationService(true, new TaskExecutorAdapter(pool));
            when(workspaceRepository.findAll()).thenReturn(List.of(workspace(12), workspace(9)));
            when(connectionService.findActiveProviderKind(12)).thenReturn(Optional.of(IntegrationKind.GITHUB));
            when(connectionService.findActiveGitHubPatConfig(12)).thenReturn(Optional.empty());
            when(connectionService.findActiveProviderKind(9)).thenReturn(Optional.of(IntegrationKind.GITLAB));
            when(connectionService.findActiveBearerToken(9, IntegrationKind.GITLAB))
                    .thenReturn(Optional.of(new BearerToken("glpat-healthy", null)));
            doAnswer(inv -> {
                        gitHubStarted.countDown();
                        assertThat(releaseGitHub.await(10, TimeUnit.SECONDS)).isTrue();
                        return null;
                    })
                    .when(gitHubTrigger)
                    .syncOnStartup(12, true);

            service.activateAllWorkspaces();

            assertThat(gitHubStarted.await(5, TimeUnit.SECONDS)).isTrue();
            verify(natsConsumer, timeout(5_000)).startConsumingScope(9L);
            verify(gitLabTrigger, timeout(5_000)).syncOnStartup(9, true);
            assertThat(releaseGitHub.getCount()).isOne();
        } finally {
            releaseGitHub.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
