package de.tum.cit.aet.hephaestus.workspace.directory;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DirectoryPolicyErasure implements WorkspacePurgeContributor, AccountErasureContributor {
    private final DirectoryPolicyRepository policies;

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        policies.deleteAllByWorkspace_Id(workspaceId);
    }

    @Override
    public void eraseAccount(long accountId) {
        policies.clearApprover(accountId);
    }

    @Override
    public int getOrder() {
        return -400;
    }
}
