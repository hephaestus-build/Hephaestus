package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AccountMembershipQueryAdapter implements AccountMembershipQuery {
    private final MembershipRepository memberships;
    private final WorkspaceRepository workspaces;

    @Override
    public List<MembershipView> membershipsForAccount(long accountId) {
        return memberships.findById_AccountId(accountId).stream()
                .filter(member -> member.getLeftAt() == null)
                .map(member -> new MembershipView(
                        member.getWorkspace().getId(),
                        member.getWorkspace().getWorkspaceSlug(),
                        member.getWorkspace().getDisplayName(),
                        switch (member.getRole()) {
                            case OWNER -> Role.OWNER;
                            case ADMIN -> Role.ADMIN;
                            case MEMBER -> Role.MEMBER;
                        }))
                .toList();
    }

    @Override
    public List<Long> administratorAccountIds(long workspaceId) {
        return memberships.findAdministratorAccountIds(workspaceId);
    }

    @Override
    public List<Long> ownerAccountIds(long workspaceId) {
        return memberships.findOwnerAccountIds(workspaceId);
    }

    @Override
    public Optional<WorkspaceView> workspace(long workspaceId) {
        return workspaces.findNotificationViewById(workspaceId);
    }

    @Override
    public boolean isActiveVisibleMember(long workspaceId, long accountId) {
        return memberships.isActiveVisibleMember(workspaceId, accountId);
    }

    @Override
    public boolean isAdministrator(long workspaceId, long accountId) {
        return memberships.isAdministrator(workspaceId, accountId);
    }
}
