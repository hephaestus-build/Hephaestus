package de.tum.cit.aet.hephaestus.practices.spi;

/**
 * Whether a workspace can actually run practice reviews. An SPI so practices does not depend on
 * agent, which would close the cycle practices → agent → practices.
 */
public interface PracticeReviewReadiness {
    /**
     * Whether the workspace has an enabled {@code PRACTICE_REVIEW} agent binding whose model is
     * available now — that is, whether a practice review would actually be submitted.
     */
    boolean hasRunnableAgent(Long workspaceId);
}
