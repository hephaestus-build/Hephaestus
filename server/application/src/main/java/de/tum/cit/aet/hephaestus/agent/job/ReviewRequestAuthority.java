package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Who may ask for a review of a given artifact, right now.
 *
 * <p>Feedback is delivered to the artifact's author, not to whoever asked, so an unchecked request path
 * would let anyone who can reach the artifact aim coaching at a colleague. The requester must therefore
 * have standing — be the artifact's author or an assignee — or be a workspace admin; ordinary membership
 * alone is not enough. Shared by every front door onto a hand-requested review (SCM bot command, REST
 * endpoint) so the rule stays true in one place.
 *
 * <p>A Hephaestus account may link several SCM identities (ADR 0017), so standing is checked against a set
 * of identities. A bot command only knows the single identity that wrote the comment, so a multi-identity
 * admin fails closed there unless that identity is the admin one, and must use the REST front door instead.
 */
@Component
public class ReviewRequestAuthority {

    private static final Set<WorkspaceRole> ADMIN_ROLES = Set.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN);

    private final AccountWorkspaceMembershipQuery memberships;
    private final AccountIdentityQuery identities;
    private final UserRepository users;

    public ReviewRequestAuthority(
            AccountWorkspaceMembershipQuery memberships, AccountIdentityQuery identities, UserRepository users) {
        this.memberships = memberships;
        this.identities = identities;
        this.users = users;
    }

    /**
     * @param artifact the artifact, with its author and assignees already fetched — a lazy collection
     *     read here would decide the question on whatever the session happened to have loaded
     * @param requester the SCM identity that asked, or {@code null} when the request could not be
     *     attributed to one — itself a refusal
     */
    public boolean mayRequest(long workspaceId, Issue artifact, @Nullable User requester) {
        return standingOf(workspaceId, artifact, requester == null ? List.of() : List.of(requester))
                .isPresent();
    }

    /**
     * Returns the identity rather than a boolean because the ledger row must name the person the rule
     * accepted. The first qualifying candidate in {@code candidates} order is used to attribute the
     * request.
     */
    public Optional<User> standingOf(long workspaceId, Issue artifact, Collection<User> candidates) {
        return candidates.stream()
                .filter(candidate -> candidate != null && candidate.getId() != null)
                .filter(candidate ->
                        isActorOn(artifact, candidate.getId()) || isWorkspaceAdmin(workspaceId, candidate.getId()))
                .findFirst();
    }

    private boolean isActorOn(Issue artifact, Long requesterId) {
        User author = artifact.getAuthor();
        if (author != null && requesterId.equals(author.getId())) {
            return true;
        }
        Set<User> assignees = artifact.getAssignees();
        return assignees != null && assignees.stream().anyMatch(a -> requesterId.equals(a.getId()));
    }

    public boolean isWorkspaceAdmin(long workspaceId, Long requesterId) {
        return users
                .findById(requesterId)
                .flatMap(user -> identities.resolveActiveAccountId(
                        Objects.requireNonNull(user.getProvider().getId()),
                        user.getNativeId().toString(),
                        null))
                .map(memberships::membershipsForAccount)
                .orElseGet(List::of)
                .stream()
                .anyMatch(membership -> membership.workspaceId().equals(workspaceId)
                        && ("OWNER".equals(membership.role()) || "ADMIN".equals(membership.role())));
    }
}
