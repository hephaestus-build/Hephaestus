package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@WorkspaceAgnostic("Slack entry points resolve a workspace from team_id before checking mentor readiness")
class DefaultMentorReadinessQuery implements MentorReadinessQuery {

    private static final Logger log = LoggerFactory.getLogger(DefaultMentorReadinessQuery.class);

    private final WorkspaceAgentBindingRepository agentBindingRepository;
    private final LlmModelResolver llmModelResolver;
    private final WorkspaceRepository workspaceRepository;

    DefaultMentorReadinessQuery(
            WorkspaceAgentBindingRepository agentBindingRepository,
            LlmModelResolver llmModelResolver,
            WorkspaceRepository workspaceRepository) {
        this.agentBindingRepository = agentBindingRepository;
        this.llmModelResolver = llmModelResolver;
        this.workspaceRepository = workspaceRepository;
    }

    @Override
    public boolean isEnabled(long workspaceId) {
        try {
            return workspaceRepository
                    .findById(workspaceId)
                    .filter(workspace -> workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
                    .map(workspace ->
                            Boolean.TRUE.equals(workspace.getFeatures().getMentorEnabled()))
                    .orElse(false);
        } catch (RuntimeException exception) {
            log.debug(
                    "Could not resolve mentor feature policy: workspaceId={}, error={}",
                    workspaceId,
                    exception.toString());
            return false;
        }
    }

    @Override
    public boolean isReady(long workspaceId) {
        if (!isEnabled(workspaceId)) {
            return false;
        }
        try {
            return agentBindingRepository.findByWorkspaceIdWithModels(workspaceId).stream()
                    .filter(binding -> binding.getPurpose() == AgentPurpose.MENTOR)
                    .filter(WorkspaceAgentBinding::isEnabled)
                    .anyMatch(llmModelResolver::isAvailable);
        } catch (RuntimeException exception) {
            log.debug(
                    "Could not resolve mentor readiness: workspaceId={}, error={}", workspaceId, exception.toString());
            return false;
        }
    }
}
