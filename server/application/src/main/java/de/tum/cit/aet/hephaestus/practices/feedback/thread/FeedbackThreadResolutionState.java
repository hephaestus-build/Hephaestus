package de.tum.cit.aet.hephaestus.practices.feedback.thread;

import de.tum.cit.aet.hephaestus.core.Lifecycle;

/** Where Hephaestus's own change to a thread stands. */
public enum FeedbackThreadResolutionState implements Lifecycle<FeedbackThreadResolutionState> {
    /** An action carries the change out. */
    PENDING,
    /** The provider confirmed the thread in the asked state. */
    DONE,
    /** Hephaestus stopped, for its stop reason. A person's change or a removed thread ends the thread's resolution. */
    STOPPED;

    @Override
    public boolean canTransitionTo(FeedbackThreadResolutionState next) {
        // Only an action confirms a change, so a confirmed change is never confirmed again without a new ask.
        return this != DONE || next != DONE;
    }
}
