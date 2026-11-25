package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.ConditionalOnEvaluation;
import de.tum.cit.aet.hephaestus.core.EvaluationWork;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnEvaluation
@RequiredArgsConstructor
class EvaluationModelGuard implements SmartInitializingSingleton {

    private final LlmConnectionRepository instance;
    private final WorkspaceLlmConnectionRepository workspaces;

    private final EvaluationWork.Workspaces scope;

    @Override
    public void afterSingletonsInstantiated() {
        var workspaceIds = scope.ids();
        if (instance.existsBySealedApiKeyIsNotNull()
                || (!workspaceIds.isEmpty()
                        && workspaces.existsByWorkspaceIdInAndSealedApiKeyIsNotNull(workspaceIds))) {
            throw new IllegalStateException("Controlled clock cannot run with stored model credentials");
        }
    }
}
