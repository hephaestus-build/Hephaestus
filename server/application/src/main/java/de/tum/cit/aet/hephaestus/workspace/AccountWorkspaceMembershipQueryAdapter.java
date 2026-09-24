package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountWorkspaceMembershipQueryAdapter implements AccountWorkspaceMembershipQuery {

    private final WorkspaceMembershipRepository workspaceMembershipRepository;
    private final CurrentAccountUsers accountUsers;
    private final AccountIdentityQuery identities;

    public AccountWorkspaceMembershipQueryAdapter(
            WorkspaceMembershipRepository workspaceMembershipRepository,
            CurrentAccountUsers accountUsers,
            AccountIdentityQuery identities) {
        this.workspaceMembershipRepository = workspaceMembershipRepository;
        this.accountUsers = accountUsers;
        this.identities = identities;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkspaceMembershipView> membershipsForAccount(long accountId) {
        List<Long> userIds = accountUsers.resolve(accountId).stream()
                .map(user -> Objects.requireNonNull(user.getId()))
                .toList();
        if (userIds.isEmpty()) {
            return List.of();
        }
        var byWorkspace = workspaceMembershipRepository.findAllWithWorkspaceByUserIdIn(userIds).stream()
                .collect(Collectors.groupingBy(
                        membership -> membership.getWorkspace().getId(), LinkedHashMap::new, Collectors.toList()));
        return byWorkspace.values().stream()
                .map(memberships -> {
                    var representative = memberships.stream()
                            .min(Comparator.comparingInt(membership ->
                                    userIds.indexOf(membership.getUser().getId())))
                            .orElseThrow();
                    var role = memberships.stream()
                            .map(WorkspaceMembership::getRole)
                            .reduce((first, next) -> first.isAtLeast(next) ? first : next)
                            .orElseThrow();
                    var workspace = representative.getWorkspace();
                    return new WorkspaceMembershipView(
                            workspace.getId(),
                            workspace.getWorkspaceSlug(),
                            workspace.getDisplayName(),
                            role.name(),
                            Objects.requireNonNull(representative.getUser().getId()));
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> administratorAccountIds(long workspaceId) {
        return activeAdministratorAccounts(workspaceId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAdministrator(long workspaceId, long accountId) {
        return activeAdministratorAccounts(workspaceId).contains(accountId);
    }

    private List<Long> activeAdministratorAccounts(long workspaceId) {
        return workspaceMembershipRepository.findAllWithUserByWorkspaceId(workspaceId).stream()
                .filter(membership -> membership.getRole().isAtLeast(WorkspaceMembership.WorkspaceRole.ADMIN))
                .map(membership -> activeAccountId(membership.getUser()))
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    private Optional<Long> activeAccountId(User user) {
        return identities.resolveActiveAccountId(
                Objects.requireNonNull(user.getProvider().getId()),
                user.getNativeId().toString(),
                null);
    }
}
