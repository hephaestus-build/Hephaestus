package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
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

    private final WorkspaceMembershipRepository memberships;

    public ReviewRequestAuthority(WorkspaceMembershipRepository memberships) {
        this.memberships = memberships;
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
                .filter(candidate -> hasStanding(workspaceId, artifact, candidate.getId()))
                .findFirst();
    }

    /** An identity the mirror has synced but never persisted has no id, and so no standing. */
    private boolean hasStanding(long workspaceId, Issue artifact, @Nullable Long candidateId) {
        return candidateId != null && (isActorOn(artifact, candidateId) || isWorkspaceAdmin(workspaceId, candidateId));
    }

    /**
     * Whether any of these identities administers the workspace, which gives standing on every artifact it owns.
     * One membership read per identity.
     */
    public boolean anyAdmin(long workspaceId, Collection<User> requesters) {
        return ids(requesters).anyMatch(id -> isWorkspaceAdmin(workspaceId, id));
    }

    /**
     * Whether any of these identities wrote the artifact or is assigned to it.
     *
     * @param artifact the artifact, with its author and assignees already fetched
     */
    public boolean isActorOn(Issue artifact, Collection<User> requesters) {
        return ids(requesters).anyMatch(id -> isActorOn(artifact, id));
    }

    private boolean isActorOn(Issue artifact, Long requesterId) {
        User author = artifact.getAuthor();
        if (author != null && requesterId.equals(author.getId())) {
            return true;
        }
        Set<User> assignees = artifact.getAssignees();
        return assignees != null && assignees.stream().anyMatch(a -> requesterId.equals(a.getId()));
    }

    /** An identity the mirror has synced but never persisted has no id to compare or look up. */
    private static Stream<Long> ids(Collection<User> users) {
        return users.stream().map(User::getId).filter(Objects::nonNull);
    }

    public boolean isWorkspaceAdmin(long workspaceId, Long requesterId) {
        return memberships
                .findByWorkspace_IdAndUser_Id(workspaceId, requesterId)
                .map(membership -> ADMIN_ROLES.contains(membership.getRole()))
                .orElse(false);
    }
}
