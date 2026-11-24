package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.ConditionalOnEvaluation;
import de.tum.cit.aet.hephaestus.core.EvaluationWork;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnEvaluation
class WorkspaceEvaluationConfiguration {

    @Bean
    EvaluationWork.Workspaces evaluationWorkspaces(WorkspaceRepository repository) {
        return repository::findEvaluationWorkspaceIds;
    }
}
