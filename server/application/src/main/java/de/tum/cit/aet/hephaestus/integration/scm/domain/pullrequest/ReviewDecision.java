package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

/**
 * The review decision state for a pull request.
 * Indicates whether the PR has been approved or requires changes.
 */
public enum ReviewDecision {
    /** Someone approved, and the approvals meet what the PR requires. */
    APPROVED,

    /** Changes have been requested by a reviewer. */
    CHANGES_REQUESTED,

    /** Not approved yet: a required approval is missing, or, on GitLab, nobody has approved. */
    REVIEW_REQUIRED,
}
