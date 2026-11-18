package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.core.Lifecycle;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Where one attempt to run a review stands. A running attempt can go back to the queue, and a completed one is queued
 * again when its composition resumes on a new run.
 */
public enum ReviewJobStatus implements Lifecycle<ReviewJobStatus> {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    TIMED_OUT,
    CANCELLED;

    private static final Map<ReviewJobStatus, Set<ReviewJobStatus>> LEGAL = Map.of(
            QUEUED,
            Set.of(RUNNING, FAILED, CANCELLED),
            RUNNING,
            Set.of(QUEUED, COMPLETED, FAILED, TIMED_OUT, CANCELLED),
            COMPLETED,
            Set.of(QUEUED),
            FAILED,
            Set.of(),
            TIMED_OUT,
            Set.of(),
            CANCELLED,
            Set.of());

    @Override
    public boolean canTransitionTo(ReviewJobStatus next) {
        return Objects.requireNonNull(LEGAL.get(this)).contains(next);
    }
}
