package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Deletes a purged workspace's sandbox rows. Their tokens stop at once, and the reaper deletes their claims, which no
 * row names any more: the purge runs in one transaction, which no Kubernetes call may hold open.
 */
@Component
@RequiredArgsConstructor
class AgentSandboxPurgeContributor implements WorkspacePurgeContributor {

    private final AgentSandboxRepository sandboxes;

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        sandboxes.deleteAllByWorkspaceId(workspaceId);
    }

    @Override
    public int getOrder() {
        return -50;
    }
}
