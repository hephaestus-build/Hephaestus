package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequestStandingLookup;
import de.tum.cit.aet.hephaestus.workspace.CurrentAccountUsers;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers {@link ReviewRequestStandingLookup} with the very rule {@link PracticeReviewRequestController}
 * enforces: {@link ReviewRequestAuthority} over the account's every SCM identity, against the artifact
 * loaded for the workspace that would pay for the review. Nothing here restates the rule, so a surface
 * cannot offer a button the front door then refuses.
 */
@Component
@RequiredArgsConstructor
class ReviewRequestStandingLookupAdapter implements ReviewRequestStandingLookup {

    /**
     * The kinds the request endpoint accepts. A conversation thread and a document are reviewed on the
     * occasion their source produces, with nothing for a person to point at and ask about.
     */
    private static final Set<ArtifactKind> REQUESTABLE = Set.of(ScmSignals.PULL_REQUEST, ScmSignals.ISSUE);

    private final CurrentAccountUsers currentAccountUsers;
    private final ReviewRequestAuthority authority;
    private final ReviewableArtifactLoader artifactLoader;

    @Override
    @Transactional(readOnly = true)
    public Set<ReviewedWorkId> mayRequest(long workspaceId, Collection<ReviewedWorkId> works) {
        Set<ReviewedWorkId> askable = works.stream()
                .filter(work -> REQUESTABLE.contains(work.kind()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (askable.isEmpty()) {
            return Set.of();
        }
        // Every identity of the account, not just the session's, exactly as the front door resolves them:
        // an admin on GitLab who signed in via GitHub is one person, not two.
        List<User> requesters = currentAccountUsers.resolve();
        if (requesters.isEmpty()) {
            return Set.of();
        }
        // A workspace admin has standing on every piece of work the workspace monitors, and every run
        // listed here is this workspace's by construction — so the answer is settled without a load.
        boolean admin = requesters.stream()
                .anyMatch(requester ->
                        requester.getId() != null && authority.isWorkspaceAdmin(workspaceId, requester.getId()));
        if (admin) {
            return Set.copyOf(askable);
        }
        return askable.stream()
                .filter(work -> hasStanding(workspaceId, work, requesters))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * One load per piece of work, with the eager graph the rule reads: the author and the assignees are
     * lazy associations, and asking the question off a half-loaded artifact would refuse an author a
     * review of their own work. A page of runs is ten rows, so this stays a bounded read rather than a
     * second copy of the rule written as SQL.
     */
    private boolean hasStanding(long workspaceId, ReviewedWorkId work, List<User> requesters) {
        return artifact(workspaceId, work)
                .filter(found ->
                        authority.standingOf(workspaceId, found, requesters).isPresent())
                .isPresent();
    }

    private Optional<Issue> artifact(long workspaceId, ReviewedWorkId work) {
        return ScmSignals.PULL_REQUEST.equals(work.kind())
                ? artifactLoader.findPullRequestForGate(workspaceId, work.id()).map(Issue.class::cast)
                : artifactLoader.findIssueForGate(workspaceId, work.id());
    }
}
