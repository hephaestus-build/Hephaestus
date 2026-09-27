package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceMemberQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WorkspaceMemberQueryAdapter implements WorkspaceMemberQuery {

    private final WorkspaceMembershipRepository membershipRepository;

    WorkspaceMemberQueryAdapter(WorkspaceMembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isMember(long workspaceId, long userId) {
        return membershipRepository.existsById(new WorkspaceMembership.Id(workspaceId, userId));
    }
}
