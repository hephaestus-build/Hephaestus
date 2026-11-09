package de.tum.cit.aet.hephaestus.core.action;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The lifecycle of one action. The trigger {@code guard_agent_action} refuses every transition that this table does
 * not list, and the CHECKs on {@code agent_action} tie the lease, the end time and the reason to the state.
 */
public enum ActionState {
    /** An agent proposed it; the server has not admitted it yet. */
    PROPOSED,
    /** Admitted, and waiting for its lane and its next attempt time. */
    ADMITTED,
    /** One executor holds the lease ({@code lease_epoch}, {@code leased_until}). */
    EXECUTING,
    /** The provider confirmed the effect. {@code readback_ref} names it when the provider gives a reference. */
    DONE,
    /** The effect may or may not exist, and no attempt will find out. */
    UNCERTAIN,
    /** The provider refused, or every attempt failed before a write could start. */
    FAILED,
    /** Nothing was written: it expired, was erased, or its owner withdrew it. */
    CANCELLED;

    private static final Map<ActionState, Set<ActionState>> LEGAL = Map.of(
            PROPOSED, Set.of(ADMITTED, CANCELLED),
            ADMITTED, Set.of(EXECUTING, CANCELLED),
            // EXECUTING -> EXECUTING is a reclaim after the lease expired; it takes a new epoch.
            EXECUTING, Set.of(ADMITTED, EXECUTING, DONE, UNCERTAIN, FAILED, CANCELLED),
            UNCERTAIN, Set.of(),
            DONE, Set.of(),
            FAILED, Set.of(),
            CANCELLED, Set.of());

    public boolean canTransitionTo(ActionState next) {
        return Objects.requireNonNull(LEGAL.get(this)).contains(next);
    }

    /** An ended action never changes again, and keeps no payload. */
    public boolean terminal() {
        return Objects.requireNonNull(LEGAL.get(this)).isEmpty();
    }
}
