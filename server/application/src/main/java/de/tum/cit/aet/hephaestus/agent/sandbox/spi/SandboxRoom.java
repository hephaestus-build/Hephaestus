package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

/**
 * A {@link SandboxBackend} whose runtime bounds the sandboxes it admits beyond what its caller already bounds, as a
 * Kubernetes ResourceQuota does. A caller claims no more work than fits, so that a sandbox the runtime refuses does
 * not cost an attempt. A backend without this bound admits whatever its caller starts.
 */
public interface SandboxRoom {

    /** How many more sandboxes of these limits the runtime admits now; {@link Integer#MAX_VALUE} for no bound. */
    int roomFor(ResourceLimits limits);
}
