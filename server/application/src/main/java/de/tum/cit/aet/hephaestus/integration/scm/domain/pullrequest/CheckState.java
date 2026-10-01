package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

/** Provider-neutral head-check state: GitHub status-check rollup or GitLab head pipeline. */
public enum CheckState {
    SUCCESS,
    FAILURE,
    PENDING,
    CANCELLED,
    /**
     * The provider reported no check state for the head: GitHub's empty status-check rollup. A GitLab record stored
     * before {@link #NO_PIPELINE} and {@link #SKIPPED} existed holds it for either of those, so it does not say which.
     */
    NONE,
    /**
     * GitLab reported that the head has no pipeline. It says nothing about whether CI is configured: a workflow rule
     * can keep a configured pipeline from being created.
     */
    NO_PIPELINE,
    /** GitLab reported the head's pipeline as skipped: neither a pass nor a failure. */
    SKIPPED,
}
