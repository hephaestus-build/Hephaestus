package de.tum.cit.aet.hephaestus.agent.handler.conversation;

/**
 * The outcome of routing one observation for conversational delivery. Only {@link #ADMIT} is prepared as a PREPARED
 * IN_CHAT feedback unit; every other value is a named, testable reason the observation is NOT raised in a mentor
 * turn.
 */
public enum ConversationRoutingDecision {
    /** Author-targeted problem with no natural inline anchor, not already delivered in-context - prepare it. */
    ADMIT,
    /** Not a problem worth coaching on (a strength, or a NOT_APPLICABLE abstention). */
    NOT_DELIVERABLE,
    /** Has a natural inline anchor (a diff location on a PR) - it belongs in-context, not in the conversation. */
    HAS_INLINE_ANCHOR,
    /** This exact observation was already delivered in-context to this recipient. */
    ALREADY_DELIVERED_IN_CONTEXT,
    /** Reviewer-targeted - deferred (ADR 0021). */
    REVIEWER_DEFERRED,
    /** The practice's autonomy is OFF: a chat turn is read on request, so approval never gates it. */
    PRACTICE_REQUIRES_APPROVAL,
    /**
     * From a backfill campaign - coaching on a decision made months ago would present retrospective
     * measurement as today's work, so it is recorded but raised nowhere.
     */
    BACKFILL_QUIET,
}
