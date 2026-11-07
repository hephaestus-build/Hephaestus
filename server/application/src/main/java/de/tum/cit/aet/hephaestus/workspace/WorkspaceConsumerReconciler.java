package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.sync.nats.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class WorkspaceConsumerReconciler {

    private final WorkspaceQueryService workspaces;
    private final IntegrationNatsConsumer consumers;

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    @WorkspaceAgnostic("Reconciles the broker subscriptions of every active workspace")
    public void reconcile() {
        for (long workspaceId : workspaces.findActiveIds()) {
            try {
                consumers.startConsumingScope(workspaceId);
                consumers.updateScopeConsumer(workspaceId);
            } catch (RuntimeException failure) {
                log.error(
                        "Failed to reconcile workspace consumers: workspaceId={}, error.type={}",
                        workspaceId,
                        failure.getClass().getName());
            }
        }
    }
}
