package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountWorkspaceMembershipQueryAdapter implements AccountWorkspaceMembershipQuery {
    private final WorkspaceAccountMembershipRepository memberships;
    private final CurrentAccountUsers accountUsers;
    private final WorkspaceMembershipRepository actors;

    public AccountWorkspaceMembershipQueryAdapter(
            WorkspaceAccountMembershipRepository memberships,
            CurrentAccountUsers accountUsers,
            WorkspaceMembershipRepository actors) {
        this.memberships = memberships;
        this.accountUsers = accountUsers;
        this.actors = actors;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkspaceMembershipView> membershipsForAccount(Long accountId) {
        var userIds = accountUsers.resolve(accountId).stream()
                .map(user -> Objects.requireNonNull(user.getId()))
                .toList();
        return memberships.findActiveByAccountId(accountId).stream()
                .map(membership -> {
                    var workspace = membership.getWorkspace();
                    var actorIds = userIds.isEmpty()
                            ? List.<Long>of()
                            : actors.findByWorkspace_IdAndUser_IdIn(workspace.getId(), userIds).stream()
                                    .map(actor -> actor.getUser().getId())
                                    .toList();
                    var representative = userIds.stream()
                            .filter(actorIds::contains)
                            .findFirst()
                            .orElse(null);
                    return new WorkspaceMembershipView(
                            workspace.getId(),
                            workspace.getWorkspaceSlug(),
                            workspace.getDisplayName(),
                            membership.getRole().name(),
                            representative);
                })
                .toList();
    }
}
