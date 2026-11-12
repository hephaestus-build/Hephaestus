package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.core.Lifecycle;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Schema(description = "Where the review stands.")
public enum ReviewStatus implements Lifecycle<ReviewStatus> {
    QUEUED,
    RUNNING,
    COMPLETE,
    PARTIAL,
    FAILED,
    CANCELLED;

    private static final Map<ReviewStatus, Set<ReviewStatus>> LEGAL = Map.of(
            QUEUED,
            Set.of(RUNNING, COMPLETE, PARTIAL, FAILED, CANCELLED),
            RUNNING,
            Set.of(RUNNING, QUEUED, COMPLETE, PARTIAL, FAILED, CANCELLED),
            COMPLETE,
            Set.of(),
            PARTIAL,
            Set.of(),
            FAILED,
            Set.of(),
            CANCELLED,
            Set.of());

    @Override
    public boolean canTransitionTo(ReviewStatus next) {
        return Objects.requireNonNull(LEGAL.get(this)).contains(next);
    }

    public boolean isTerminal() {
        return this == COMPLETE || this == PARTIAL || this == FAILED || this == CANCELLED;
    }
}
