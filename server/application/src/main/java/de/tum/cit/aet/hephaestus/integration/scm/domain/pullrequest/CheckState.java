package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

/**
 * What the provider's checks say about a pull request's head, as one fact both providers fill:
 * GitHub's status check rollup over the head commit, GitLab's head pipeline. The adapter folds the
 * provider's vocabulary into these five; a review reads the state beside the head it was observed
 * for.
 */
public enum CheckState {
    SUCCESS,
    FAILURE,
    PENDING,
    CANCELLED,
    /** The provider reported no check for the head: none configured, or every one skipped. */
    NONE,
}
