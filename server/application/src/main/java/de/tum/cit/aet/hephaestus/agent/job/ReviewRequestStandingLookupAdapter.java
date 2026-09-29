package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequestStandingLookup;
import de.tum.cit.aet.hephaestus.workspace.CurrentAccountUsers;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers {@link ReviewRequestStandingLookup} with the rule {@link PracticeReviewRequestController} enforces:
 * {@link ReviewRequestAuthority} over the account's every SCM identity, against the artifact as the gate loads
 * it for this workspace. Every page first reads the membership of each identity. An admin's standing does not
 * depend on the work, so it then costs one ownership read per kind of work on the page; anyone else's costs one
 * ownership read and one gate load per distinct work on the page.
 */
@Component
@RequiredArgsConstructor
class ReviewRequestStandingLookupAdapter implements ReviewRequestStandingLookup {

    /** The kinds the request endpoint accepts, by the entity type that stores each. */
    private static final Map<ArtifactKind, Class<? extends Issue>> REQUESTABLE =
            Map.of(ArtifactKinds.PULL_REQUEST, PullRequest.class, ArtifactKinds.ISSUE, Issue.class);

    private final CurrentAccountUsers currentAccountUsers;
    private final ReviewRequestAuthority authority;
    private final ReviewableArtifactLoader artifactLoader;
    private final ReviewableArtifactOwnershipRepository ownership;

    @Override
    @Transactional(readOnly = true)
    public Set<ReviewedWorkId> mayRequest(long workspaceId, Collection<ReviewedWorkId> works) {
        // A user view is read-only, and the identities here would be the administrator's, not the viewed user's.
        if (UserViewContextHolder.get() != null) {
            return Set.of();
        }
        Set<ReviewedWorkId> requestable = works.stream()
                .filter(work -> REQUESTABLE.containsKey(work.kind()))
                .collect(Collectors.toUnmodifiableSet());
        List<User> requesters = currentAccountUsers.resolve();
        if (requestable.isEmpty() || requesters.isEmpty()) {
            return Set.of();
        }
        if (authority.anyAdmin(workspaceId, requesters)) {
            return inWorkspace(workspaceId, requestable);
        }
        return requestable.stream()
                .filter(work -> artifact(workspaceId, work)
                        .filter(artifact -> authority.isActorOn(artifact, requesters))
                        .isPresent())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The works the workspace owns, one read per kind. */
    private Set<ReviewedWorkId> inWorkspace(long workspaceId, Set<ReviewedWorkId> works) {
        return works.stream()
                .collect(Collectors.groupingBy(
                        ReviewedWorkId::kind, Collectors.mapping(ReviewedWorkId::id, Collectors.toList())))
                .entrySet()
                .stream()
                .flatMap(kind -> ownership
                        .findIdsInWorkspace(
                                workspaceId, Objects.requireNonNull(REQUESTABLE.get(kind.getKey())), kind.getValue())
                        .stream()
                        .map(id -> new ReviewedWorkId(kind.getKey(), id)))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** With the author and assignees fetched: the rule reads both, and both are lazy. */
    private Optional<? extends Issue> artifact(long workspaceId, ReviewedWorkId work) {
        return work.kind().equals(ArtifactKinds.PULL_REQUEST)
                ? artifactLoader.findPullRequestForGate(workspaceId, work.id())
                : artifactLoader.findIssueForGate(workspaceId, work.id());
    }
}
