package de.tum.cit.aet.hephaestus.workspace;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorkspaceLifecycleServiceTest extends BaseUnitTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resumeStartsConsumptionOnlyAfterCommit(boolean committed) {
        var repository = mock(WorkspaceRepository.class);
        var consumers = mock(IntegrationNatsConsumer.class);
        var properties = mock(NatsConnectionProperties.class);
        var service = new WorkspaceLifecycleService(
                properties,
                repository,
                new StaticListableBeanFactory(Map.of("consumers", consumers))
                        .getBeanProvider(IntegrationNatsConsumer.class),
                mock(),
                mock(),
                List.of(),
                List.of(),
                mock());
        Workspace workspace = new Workspace();
        workspace.setId(7L);
        workspace.setWorkspaceSlug("resume");
        workspace.setStatus(Workspace.WorkspaceStatus.SUSPENDED);
        when(repository.findByWorkspaceSlugForUpdate("resume")).thenReturn(Optional.of(workspace));
        when(repository.save(workspace)).thenReturn(workspace);
        when(properties.enabled()).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.resumeWorkspace("resume");
            verifyNoInteractions(consumers);
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            TransactionSynchronizationManager.clearSynchronization();
            if (committed) callbacks.forEach(TransactionSynchronization::afterCommit);
            callbacks.forEach(callback -> callback.afterCompletion(
                    committed
                            ? TransactionSynchronization.STATUS_COMMITTED
                            : TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(consumers, times(committed ? 1 : 0)).startConsumingScope(7L);
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }
}
