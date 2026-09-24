package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

/** Provider-neutral head-check state: GitHub status-check rollup or GitLab head pipeline. */
public enum CheckState {
    SUCCESS,
    FAILURE,
    PENDING,
    CANCELLED,
    /** The provider reported no check for the head: none configured, or every one skipped. */
    NONE,
}
