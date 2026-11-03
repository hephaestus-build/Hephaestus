package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.event.AgentContextChangedEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

final class ConnectionLifecycleEffects {
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate providerTeardownTemplate;

    ConnectionLifecycleEffects(
            ApplicationEventPublisher eventPublisher, PlatformTransactionManager transactionManager) {
        this.eventPublisher = eventPublisher;
        this.providerTeardownTemplate = new TransactionTemplate(transactionManager);
        this.providerTeardownTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    void revoke(Runnable revoke) {
        providerTeardownTemplate.executeWithoutResult(status -> revoke.run());
    }

    /**
     * Publishes an ACTIVE-boundary crossing to provider adapters inside the transition transaction.
     * A rollback drops the event, and the state machine calls this only after an actual state change.
     */
    void publishLifecycleEvent(Connection connection, IntegrationState previous, IntegrationState next) {
        long workspaceId = connection.getWorkspace().getId();
        if (next == IntegrationState.ACTIVE) {
            eventPublisher.publishEvent(
                    new ConnectionLifecycleEvent.Activated(connection.getId(), workspaceId, connection.getKind()));
        } else if (previous == IntegrationState.ACTIVE) {
            eventPublisher.publishEvent(
                    new ConnectionLifecycleEvent.Deactivated(connection.getId(), workspaceId, connection.getKind()));
            // GitHub, GitLab, Slack or Outline: a running Heph turn may hold what the connection made readable.
            eventPublisher.publishEvent(new AgentContextChangedEvent(workspaceId));
        }
    }
}
