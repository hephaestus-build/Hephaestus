package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceMemberships;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkspaceMembershipsAdapter implements WorkspaceMemberships {
    private final MembershipRepository memberships;

    @Override
    public Optional<WorkspaceRole> activeRole(long workspaceId, long accountId) {
        return memberships
                .findByWorkspace_IdAndId_AccountId(workspaceId, accountId)
                .filter(member -> member.getLeftAt() == null)
                .map(Membership::getRole);
    }
}
