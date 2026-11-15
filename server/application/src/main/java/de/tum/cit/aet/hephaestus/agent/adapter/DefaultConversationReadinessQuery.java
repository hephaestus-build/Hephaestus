package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.ModelPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignment;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignmentRepository;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationReadinessQuery;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@WorkspaceAgnostic("Slack entry points resolve a workspace from team_id before checking conversation readiness")
class DefaultConversationReadinessQuery implements ConversationReadinessQuery {

    private static final Logger log = LoggerFactory.getLogger(DefaultConversationReadinessQuery.class);

    private final WorkspaceModelAssignmentRepository modelAssignmentRepository;
    private final LlmModelResolver llmModelResolver;
    private final WorkspaceQueries workspaceQueries;

    DefaultConversationReadinessQuery(
            WorkspaceModelAssignmentRepository modelAssignmentRepository,
            LlmModelResolver llmModelResolver,
            WorkspaceQueries workspaceQueries) {
        this.modelAssignmentRepository = modelAssignmentRepository;
        this.llmModelResolver = llmModelResolver;
        this.workspaceQueries = workspaceQueries;
    }

    @Override
    public boolean isReady(long workspaceId) {
        try {
            boolean active = workspaceQueries
                    .findById(workspaceId)
                    .filter(workspace -> workspace.active())
                    .isPresent();
            return active
                    && modelAssignmentRepository.findByWorkspaceIdWithModels(workspaceId).stream()
                            .filter(binding -> binding.getPurpose() == ModelPurpose.CONVERSATION)
                            .filter(WorkspaceModelAssignment::isEnabled)
                            .anyMatch(llmModelResolver::isAvailable);
        } catch (RuntimeException exception) {
            log.debug(
                    "Could not resolve conversation readiness: workspaceId={}, error={}",
                    workspaceId,
                    exception.toString());
            return false;
        }
    }
}
