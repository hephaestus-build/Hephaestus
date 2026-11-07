package de.tum.cit.aet.hephaestus.agent.proxy;

/** Why the sandbox gateway refused a request itself. A provider's own refusal that the proxy relays is none of these. */
enum SandboxGatewayRefusal {
    /** The source address spent its requests, before any credential was read. */
    SOURCE_LIMIT,
    /** The authenticated caller spent its requests. */
    PRINCIPAL_LIMIT,
    /** The body is too large, or declares no length. */
    OVERSIZED,
    /** The gateway already holds as many bodies as its heap allows. */
    BODY_BUDGET,
    /** The credential is missing or invalid, or the address is not private. */
    UNAUTHENTICATED,
    /** The credential is valid, and its attempt no longer holds its lease. A normal end of an attempt. */
    ATTEMPT_ENDED,
    /** The credential is valid, and its kind may not call this capability. */
    FORBIDDEN,
    /** No capability has this path. */
    UNKNOWN_ROUTE
}
