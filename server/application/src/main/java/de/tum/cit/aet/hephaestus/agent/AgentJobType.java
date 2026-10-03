package de.tum.cit.aet.hephaestus.agent;

/**
 * Discriminator for {@code AgentJob} that dispatches to the appropriate {@code JobTypeHandler}.
 *
 * <p>Each value corresponds to a handler implementation that knows how to prepare the Docker
 * volume, parse output, and deliver results.
 */
public enum AgentJobType {
    /** Practice review of a pull/merge request's diff, comments, and review state. */
    PULL_REQUEST_REVIEW,
    /** Practice review of an issue's body, comment thread, and lifecycle state. */
    ISSUE_REVIEW,
    /** Practice review of a settled Slack conversation thread. */
    CONVERSATION_REVIEW,
    /** Practice review of one mirrored wiki document — its prose, its collection, and who wrote it. */
    DOCUMENT_REVIEW,
}
