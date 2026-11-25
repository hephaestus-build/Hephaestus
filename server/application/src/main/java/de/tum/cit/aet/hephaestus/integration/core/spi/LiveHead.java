package de.tum.cit.aet.hephaestus.integration.core.spi;

/** Whether the reviewed version is still the head of the work, as the provider answers now. */
public enum LiveHead {
    /** The provider's head is the reviewed version. */
    CURRENT,
    /** The provider's head moved past the reviewed version, whether or not this server has seen the push yet. */
    MOVED,
    /** The provider confirms the source branch of open work is missing. */
    SOURCE_BRANCH_MISSING,
    /** The provider did not answer, or its rate limit allows no read now. */
    UNKNOWN
}
