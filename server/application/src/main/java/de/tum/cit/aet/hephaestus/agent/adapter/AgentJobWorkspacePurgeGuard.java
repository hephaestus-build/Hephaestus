package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeBlockedException;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeGuard;
import org.springframework.stereotype.Component;

@Component
class AgentJobWorkspacePurgeGuard implements WorkspacePurgeGuard {

    private final AgentJobRepository agentJobRepository;

    AgentJobWorkspacePurgeGuard(AgentJobRepository agentJobRepository) {
        this.agentJobRepository = agentJobRepository;
    }

    @Override
    public void verifyQuiescent(Long workspaceId) {
        if (agentJobRepository.existsPurgeBlockingWork(workspaceId)) {
            throw new WorkspacePurgeBlockedException(
                    "The server cannot delete this workspace while AI runs are queued, running or waiting for feedback delivery. "
                            + "Cancel queued and running runs. Wait until pending feedback delivery finishes. Then try again.");
        }
    }
}
