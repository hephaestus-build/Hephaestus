package de.tum.cit.aet.hephaestus.agent;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelWorkspaceGrantRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentWorkspacePurgeAdapter implements WorkspacePurgeContributor {

    private final ReviewJobRepository jobRepository;
    private final AgentRuns runs;
    private final WorkspaceAgentBindingRepository bindingRepository;
    private final WorkspaceLlmModelRepository modelRepository;
    private final WorkspaceLlmConnectionRepository connectionRepository;
    private final LlmModelWorkspaceGrantRepository grantRepository;

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        jobRepository.deleteAllByWorkspaceId(workspaceId);
        // After the domain rows that point to them.
        runs.deleteWorkspace(workspaceId);
        bindingRepository.deleteAllByWorkspaceId(workspaceId);
        modelRepository.deleteAllByWorkspaceId(workspaceId);
        connectionRepository.deleteAllByWorkspaceId(workspaceId);
        grantRepository.deleteAllByWorkspaceId(workspaceId);
    }

    @Override
    public int getOrder() {
        return -50;
    }
}
