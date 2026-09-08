package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class WorkspaceAccountMembershipErasure implements AccountErasureContributor, WorkspacePurgeContributor {
    private final WorkspaceAccountMembershipRepository memberships;

    @Override
    public void eraseAccount(long accountId) {
        memberships.deleteAllByAccountId(accountId);
    }

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        memberships.deleteAllByWorkspace_Id(workspaceId);
    }
}
