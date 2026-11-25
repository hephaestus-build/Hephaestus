package de.tum.cit.aet.hephaestus.agent.proxy;

/** Which of the model proxy's own rules refused a call; the proxy names it in {@link LlmProxyService#REFUSAL_HEADER}. */
public enum ProxyRefusal {
    /** The workspace's AI choice no longer permits the model. */
    AI_CHOICE,
    /** The budget that funds the call is spent. */
    BUDGET,
    /** The instance or reporter reached the daily draft limit. */
    DRAFT_QUOTA,
    /** The attempt's work deadline has passed. */
    WORK_TIME,
    /** The attempt was ended while the call waited. */
    ATTEMPT_ENDED,
    /** The workspace's authority narrowed after the work was requested, so its run ended before the call (ADR 0134). */
    AUTHORITY_CHANGED,
    /** The endpoint could not grant capacity before the attempt ended. */
    CAPACITY,
    /** The attempt already had its most model calls in flight, or the gateway its most request bodies: ask again. */
    CALLS_IN_FLIGHT,
    /** A compose run's request holds a part that the server did not issue or serve to its claim. */
    NOT_COMPOSER_REQUEST,
    /** A sandbox's chat call does not stream, so the proxy could not tell a long generation from a stalled one. */
    NOT_STREAMED
}
