package de.tum.cit.aet.hephaestus.agent.proxy;

/** Which of the model proxy's own rules refused a call; the proxy names it in {@link LlmProxyService#REFUSAL_HEADER}. */
public enum ProxyRefusal {
    /** The workspace's AI choice no longer permits the model. */
    AI_CHOICE,
    /** The budget that funds the call is spent. */
    BUDGET,
    /** The attempt's work deadline has passed. */
    WORK_TIME,
    /** The attempt was ended while the call waited. */
    ATTEMPT_ENDED,
    /** The endpoint could not grant capacity before the attempt ended. */
    CAPACITY,
    /** The attempt already had its most model calls in flight, or the gateway its most request bodies: ask again. */
    CALLS_IN_FLIGHT
}
