package de.tum.cit.aet.hephaestus.integration.submission;

import de.tum.cit.aet.hephaestus.core.Lifecycle;

/**
 * Whether a submitted version still holds its content. A version leaves {@link #AVAILABLE} once and never returns; only
 * an erasure renames the end of an ended one. The database enforces this for every writer.
 */
public enum TeamVersionState implements Lifecycle<TeamVersionState> {
    AVAILABLE,
    /** Its submitter left the team, or the team or item was removed, or a member withdrew it. */
    WITHDRAWN,
    EXPIRED,
    /** Its submitter erased their account. */
    ERASED;

    @Override
    public boolean canTransitionTo(TeamVersionState next) {
        return next != this && next != AVAILABLE && (this == AVAILABLE || next == ERASED);
    }
}
