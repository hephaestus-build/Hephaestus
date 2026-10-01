package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Loads the reviewable SCM artifact a job targets — for the workspace that is paying for it, and with the
 * eager-fetch graph its review path needs. Thin on purpose: it exists so {@link DevTriggerController} does
 * not depend on a {@code @Repository}, which the architecture rules forbid a controller to do.
 *
 * <p>The workspace ownership check matters because nothing in a surrogate artifact id relates it to a
 * workspace: without it, submitting an artifact under the wrong workspace would bill that workspace's
 * {@code agent_job} and LLM usage ledger for another one's work, and quietly skew what a backfill campaign
 * cost estimator later tells a different workspace.
 */
@Component
class ReviewableArtifactLoader {

    private final PullRequestRepository pullRequestRepository;
    private final IssueRepository issueRepository;
    private final ReviewableArtifactOwnershipRepository ownership;

    ReviewableArtifactLoader(
            PullRequestRepository pullRequestRepository,
            IssueRepository issueRepository,
            ReviewableArtifactOwnershipRepository ownership) {
        this.pullRequestRepository = pullRequestRepository;
        this.issueRepository = issueRepository;
        this.ownership = ownership;
    }

    /** Empty both when no such pull request exists and when this workspace does not monitor it. */
    Optional<PullRequest> findPullRequestForGate(long workspaceId, long pullRequestId) {
        if (!ownership.belongsToWorkspace(workspaceId, PullRequest.class, pullRequestId)) {
            return Optional.empty();
        }
        return pullRequestRepository.findByIdWithAllForGate(pullRequestId);
    }

    /**
     * Empty both when no such issue exists and when this workspace does not monitor it.
     *
     * <p>Assignees must be fetched too: the gate's role check iterates them.
     */
    Optional<Issue> findIssueForGate(long workspaceId, long issueId) {
        if (!ownership.belongsToWorkspace(workspaceId, Issue.class, issueId)) {
            return Optional.empty();
        }
        return issueRepository.findByIdWithRepositoryAndAssignees(issueId);
    }
}
