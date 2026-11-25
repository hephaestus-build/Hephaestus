package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.ConditionalOnEvaluation;
import de.tum.cit.aet.hephaestus.core.EvaluationWork;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnEvaluation
@RequiredArgsConstructor
class EvaluationConnectionGuard implements SmartInitializingSingleton {

    private final ConnectionRepository connections;

    private final EvaluationWork.Workspaces scope;

    @Override
    public void afterSingletonsInstantiated() {
        var workspaceIds = scope.ids();
        if (!workspaceIds.isEmpty()
                && connections.existsByWorkspace_IdInAndCredentialsEncryptedIsNotNull(workspaceIds)) {
            throw new IllegalStateException("Controlled clock cannot run with stored integration credentials");
        }
    }
}
