package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.core.Lifecycle;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Where one practice stands in its review. Capture decides a queued practice Ready, Withheld or Failed; a retried
 * capture decides a Ready or Running practice again. Done, Withheld and Failed are final.
 */
public enum ReviewPracticeStatus implements Lifecycle<ReviewPracticeStatus> {
    QUEUED,
    READY,
    RUNNING,
    DONE,
    WITHHELD,
    FAILED;

    private static final Map<ReviewPracticeStatus, Set<ReviewPracticeStatus>> LEGAL = Map.of(
            QUEUED,
            Set.of(READY, RUNNING, DONE, WITHHELD, FAILED),
            READY,
            Set.of(RUNNING, DONE, WITHHELD, FAILED),
            RUNNING,
            Set.of(READY, DONE, WITHHELD, FAILED),
            DONE,
            Set.of(),
            WITHHELD,
            Set.of(),
            FAILED,
            Set.of());

    @Override
    public boolean canTransitionTo(ReviewPracticeStatus next) {
        return Objects.requireNonNull(LEGAL.get(this)).contains(next);
    }
}
