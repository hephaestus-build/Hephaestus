package de.tum.cit.aet.hephaestus.integration.scm.github.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobHandle;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.GitHubDataSyncService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepositoryMonitorService;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.AsyncTaskExecutor;

@Tag("unit")
class GitHubWorkspaceDataSyncTriggerTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 41L;
    private static final long CONNECTION_ID = 99L;
    private static final long INSTALLATION_ID = 4242L;

    private final GitHubDataSyncService dataSyncService = mock(GitHubDataSyncService.class);
    private final ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
    private final SyncJobService syncJobService = mock(SyncJobService.class);
    private final WorkspaceRepositoryMonitorService monitorService = mock(WorkspaceRepositoryMonitorService.class);
    private final Connection connection = mock(Connection.class);
    private final SyncJobHandle handle = mock(SyncJobHandle.class);

    @Test
    void singleTargetSyncRunsInsideLifecycleJobForActiveConnection() {
        var dataSyncService = mock(GitHubDataSyncService.class);
        var targetProvider = mock(SyncTargetProvider.class);
        var connectionRepository = mock(ConnectionRepository.class);
        var syncJobService = mock(SyncJobService.class);
        var target = mock(SyncTarget.class);
        var connection = mock(Connection.class);
        var executor = mock(AsyncTaskExecutor.class);

        when(target.scopeId()).thenReturn(41L);
        when(targetProvider.findSyncTargetById(7L)).thenReturn(Optional.of(target));
        when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        41L, IntegrationKind.GITHUB, IntegrationState.ACTIVE))
                .thenReturn(Optional.of(connection));
        when(connection.getId()).thenReturn(99L);
        doAnswer(invocation -> {
                    Consumer<SyncJobHandle> body = invocation.getArgument(1);
                    body.accept(null);
                    return null;
                })
                .when(syncJobService)
                .run(any(SyncJobRequest.class), any());
        doAnswer(invocation -> {
                    invocation.<Runnable>getArgument(0).run();
                    return null;
                })
                .when(executor)
                .execute(any(Runnable.class));

        var trigger = new GitHubWorkspaceDataSyncTrigger(
                requiredProvider(dataSyncService),
                optionalProvider(targetProvider),
                optionalProvider(connectionRepository),
                optionalProvider(syncJobService),
                absentProvider(),
                executor);

        trigger.syncSingleSyncTarget(7L);

        ArgumentCaptor<SyncJobRequest> request = ArgumentCaptor.forClass(SyncJobRequest.class);
        verify(syncJobService).run(request.capture(), any());
        assertThat(request.getValue())
                .extracting(
                        SyncJobRequest::workspaceId,
                        SyncJobRequest::connectionId,
                        SyncJobRequest::type,
                        SyncJobRequest::trigger)
                .containsExactly(41L, 99L, SyncJobType.INITIAL, SyncJobTrigger.LIFECYCLE);
        verify(dataSyncService).syncSyncTarget(target);
    }

    @Test
    void shouldReconcileAppRepositoriesWithoutFullSyncWhenFullStartupSyncIsDisabled() {
        activeConnection(new ConnectionConfig.GitHubAppConfig(INSTALLATION_ID, null, null, Set.of()));
        runJobBodies();

        startupTrigger(optionalProvider(syncJobService)).syncOnStartup(WORKSPACE_ID, false);

        assertThat(lifecycleJob().type()).isEqualTo(SyncJobType.RECONCILIATION);
        verify(monitorService).ensureAllInstallationRepositoriesCovered(INSTALLATION_ID, null, true);
        verifyNoInteractions(dataSyncService);
    }

    @Test
    void shouldReconcileAppRepositoriesBeforeFullSyncInOneLifecycleJobWhenFullStartupSyncIsEnabled() {
        activeConnection(new ConnectionConfig.GitHubAppConfig(INSTALLATION_ID, null, null, Set.of()));
        runJobBodies();

        startupTrigger(optionalProvider(syncJobService)).syncOnStartup(WORKSPACE_ID, true);

        assertThat(lifecycleJob())
                .extracting(
                        SyncJobRequest::workspaceId,
                        SyncJobRequest::connectionId,
                        SyncJobRequest::type,
                        SyncJobRequest::trigger)
                .containsExactly(WORKSPACE_ID, CONNECTION_ID, SyncJobType.RECONCILIATION, SyncJobTrigger.LIFECYCLE);
        InOrder order = inOrder(monitorService, dataSyncService);
        order.verify(monitorService).ensureAllInstallationRepositoriesCovered(INSTALLATION_ID, null, true);
        order.verify(dataSyncService).syncAllRepositories(WORKSPACE_ID, handle);
        verify(dataSyncService, never()).syncAllRepositories(anyLong());
    }

    @Test
    void shouldSkipAppStartupWorkWhenAnotherJobIsActive() {
        activeConnection(new ConnectionConfig.GitHubAppConfig(INSTALLATION_ID, null, null, Set.of()));
        SyncJob activeJob = mock(SyncJob.class);
        when(activeJob.getId()).thenReturn(7L);
        when(activeJob.getConnection()).thenReturn(connection);
        doThrow(new SyncJobConflictException(activeJob)).when(syncJobService).run(any(SyncJobRequest.class), any());

        startupTrigger(optionalProvider(syncJobService)).syncOnStartup(WORKSPACE_ID, true);

        verifyNoInteractions(monitorService, dataSyncService);
    }

    @Test
    void shouldSkipAppStartupWorkWhenSyncJobServiceIsUnavailable() {
        when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        WORKSPACE_ID, IntegrationKind.GITHUB, IntegrationState.ACTIVE))
                .thenReturn(Optional.of(connection));
        when(connection.getConfig())
                .thenReturn(new ConnectionConfig.GitHubAppConfig(INSTALLATION_ID, null, null, Set.of()));

        startupTrigger(absentProvider()).syncOnStartup(WORKSPACE_ID, true);

        verifyNoInteractions(monitorService, dataSyncService);
    }

    @Test
    void shouldRunPatStartupSyncAsInitialLifecycleJobOnlyWhenFullStartupSyncIsEnabled() {
        activeConnection(new ConnectionConfig.GitHubPatConfig(null, null, Set.of()));
        runJobBodies();
        GitHubWorkspaceDataSyncTrigger trigger = startupTrigger(optionalProvider(syncJobService));

        trigger.syncOnStartup(WORKSPACE_ID, false);
        verifyNoInteractions(syncJobService, dataSyncService);

        trigger.syncOnStartup(WORKSPACE_ID, true);
        assertThat(lifecycleJob().type()).isEqualTo(SyncJobType.INITIAL);
        verify(dataSyncService).syncAllRepositories(WORKSPACE_ID, handle);
        verifyNoInteractions(monitorService);
    }

    private void activeConnection(ConnectionConfig config) {
        when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        WORKSPACE_ID, IntegrationKind.GITHUB, IntegrationState.ACTIVE))
                .thenReturn(Optional.of(connection));
        when(connection.getConfig()).thenReturn(config);
        when(connection.getId()).thenReturn(CONNECTION_ID);
    }

    private void runJobBodies() {
        doAnswer(invocation -> {
                    Consumer<SyncJobHandle> body = invocation.getArgument(1);
                    body.accept(handle);
                    return null;
                })
                .when(syncJobService)
                .run(any(SyncJobRequest.class), any());
    }

    private SyncJobRequest lifecycleJob() {
        ArgumentCaptor<SyncJobRequest> request = ArgumentCaptor.forClass(SyncJobRequest.class);
        verify(syncJobService).run(request.capture(), any());
        return request.getValue();
    }

    private GitHubWorkspaceDataSyncTrigger startupTrigger(ObjectProvider<SyncJobService> jobServiceProvider) {
        return new GitHubWorkspaceDataSyncTrigger(
                lazyProvider(dataSyncService),
                absentProvider(),
                optionalProvider(connectionRepository),
                jobServiceProvider,
                lazyProvider(monitorService),
                mock(AsyncTaskExecutor.class));
    }

    private static <T> ObjectProvider<T> requiredProvider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(value);
        return provider;
    }

    /** A provider whose value a test may not need, for example when the step that uses it is skipped. */
    private static <T> ObjectProvider<T> lazyProvider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        lenient().when(provider.getObject()).thenReturn(value);
        return provider;
    }

    private static <T> ObjectProvider<T> optionalProvider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static <T> ObjectProvider<T> absentProvider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        return provider;
    }
}
