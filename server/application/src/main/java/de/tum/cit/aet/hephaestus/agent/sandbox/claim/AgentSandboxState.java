package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

/**
 * Where a sandbox is in its life. A row whose claim is gone is deleted, so no state names that. The database refuses
 * every other transition (ADR 0078).
 *
 * <ul>
 *   <li>{@link #REQUESTED} → {@link #READY}: the sandbox called the gateway for the first time.
 *   <li>{@link #REQUESTED} or {@link #READY} → {@link #DELETING}: the reaper or a release took it back.
 * </ul>
 */
public enum AgentSandboxState {
    /** The claim exists, or is being created, and the sandbox has not called the gateway yet. */
    REQUESTED,
    /** The sandbox called the gateway: its Pod runs and its loop works. */
    READY,
    /** The claim is being deleted. The sandbox's token no longer works. */
    DELETING
}
