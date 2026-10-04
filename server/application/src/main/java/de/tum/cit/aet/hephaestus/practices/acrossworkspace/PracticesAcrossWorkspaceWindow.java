package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;

/**
 * The span the page's tiles read evidence over. Each window is checked against {@link CohortPrivacyPolicy} on its
 * own; the splits read the current standing and take no window.
 */
public enum PracticesAcrossWorkspaceWindow {
    /** Every observation recorded, with no lower bound. */
    ALL_TIME(null),
    DAYS_30(30),
    DAYS_90(90);

    private final @Nullable Integer days;

    PracticesAcrossWorkspaceWindow(@Nullable Integer days) {
        this.days = days;
    }

    /** Where the window starts when it ends at {@code until}; the epoch for {@link #ALL_TIME}, which has no start. */
    public Instant since(Instant until) {
        return days == null ? Instant.EPOCH : until.minus(days, ChronoUnit.DAYS);
    }
}
