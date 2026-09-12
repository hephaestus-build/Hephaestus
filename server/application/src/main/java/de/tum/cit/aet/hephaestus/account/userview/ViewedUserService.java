package de.tum.cit.aet.hephaestus.account.userview;

import de.tum.cit.aet.hephaestus.account.userview.UserViewUsersController.UserViewUserDTO;
import de.tum.cit.aet.hephaestus.core.auth.spi.UserViewAccess;
import de.tum.cit.aet.hephaestus.core.auth.spi.UserViewAccess.LinkedAccount;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves the member an instance administrator may view. An SCM actor is not an account. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ViewedUserService {

    private final WorkspaceMembershipRepository memberships;
    private final UserViewAccess access;

    public Page<UserViewUserDTO> list(Long workspaceId, Pageable pageable) {
        var members = memberships.findHumanMembers(workspaceId, pageable);
        var accounts = access.linkedAccounts(
                members.getContent().stream().map(m -> m.getUser().getId()).toList());
        return members.map(
                member -> describe(member, accounts.get(member.getUser().getId())));
    }

    public UserViewUserDTO requireUser(Long workspaceId, Long userId) {
        return memberships
                .findByWorkspace_IdAndUser_Id(workspaceId, userId)
                .filter(WorkspaceMembership::hasHumanUser)
                .map(member ->
                        describe(member, access.linkedAccounts(List.of(userId)).get(userId)))
                .orElseThrow(() -> new EntityNotFoundException("User", userId.toString()));
    }

    private static UserViewUserDTO describe(WorkspaceMembership member, @Nullable LinkedAccount account) {
        var user = member.getUser();
        return new UserViewUserDTO(
                user.getId(),
                user.getLogin(),
                user.getName(),
                account == null ? null : account.accountId(),
                account == null ? null : account.status());
    }
}
