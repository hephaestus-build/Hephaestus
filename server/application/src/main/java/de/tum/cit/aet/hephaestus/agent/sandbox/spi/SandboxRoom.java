package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

/**
 * A {@link SandboxBackend} whose runtime bounds the sandboxes it admits beyond what its caller already bounds, as a
 * Kubernetes ResourceQuota does. A caller claims no more work than fits, so that a sandbox the runtime refuses does
 * not cost an attempt. A backend without this bound admits whatever its caller starts.
 */
public interface SandboxRoom {

    int UNKNOWN_ROOM = -1;

    /**
     * How many more sandboxes of these limits the runtime admits now; {@link Integer#MAX_VALUE} for no bound,
     * {@link #UNKNOWN_ROOM} when the runtime has no current observation.
     */
    int roomFor(ResourceLimits limits);

    /** Whether no room of the runtime can ever hold one sandbox of these limits, so that waiting does not help. */
    boolean neverFits(ResourceLimits limits);

    /** Whether a review sandbox runs now, so that its end frees room; true when the runtime cannot tell. */
    boolean reviewsRunning();
}
