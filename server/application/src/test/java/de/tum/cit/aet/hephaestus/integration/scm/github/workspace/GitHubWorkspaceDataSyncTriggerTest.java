package de.tum.cit.aet.hephaestus.integration.scm.github.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobHandle;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.GitHubDataSyncService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.AsyncTaskExecutor;

@Tag("unit")
class GitHubWorkspaceDataSyncTriggerTest extends BaseUnitTest {

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

    private static <T> ObjectProvider<T> requiredProvider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(value);
        return provider;
    }

    private static <T> ObjectProvider<T> optionalProvider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
