package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountWorkspaceMembershipQueryAdapter implements AccountWorkspaceMembershipQuery {

    private final WorkspaceMembershipRepository workspaceMembershipRepository;
    private final CurrentAccountUsers accountUsers;

    public AccountWorkspaceMembershipQueryAdapter(
            WorkspaceMembershipRepository workspaceMembershipRepository, CurrentAccountUsers accountUsers) {
        this.workspaceMembershipRepository = workspaceMembershipRepository;
        this.accountUsers = accountUsers;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkspaceMembershipView> membershipsForAccount(Long accountId) {
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
                    // Keep attribution stable across role changes; authorization unions all linked roles.
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
}
