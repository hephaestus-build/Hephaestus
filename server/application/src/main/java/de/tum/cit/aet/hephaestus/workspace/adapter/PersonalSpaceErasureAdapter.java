package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A personal space ends with its owner's account: no one else could ever enter it. The erasure of an account, by the
 * person or on their request by an instance admin, removes the owner's membership and then the space.
 */
@Component
@RequiredArgsConstructor
class PersonalSpaceErasureAdapter implements AccountErasureContributor {

    private final MembershipRepository memberships;
    private final WorkspaceRepository workspaces;

    @Override
    public void eraseAccount(long accountId) {
        memberships.deletePersonalOwner(accountId);
        workspaces.deletePersonalSpace(accountId);
    }
}
