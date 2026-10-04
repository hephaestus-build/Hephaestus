package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService;
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

    /** Whether the window is the Practice profile's own look-back, the span the splits read already. */
    public boolean isProfileLookBack() {
        return days != null && days == PracticeStandingService.LOOKBACK_DAYS;
    }

    /** Where the window starts when it ends at {@code until}; null for {@link #ALL_TIME}, which has no start. */
    public @Nullable Instant since(Instant until) {
        return days == null ? null : until.minus(days, ChronoUnit.DAYS);
    }
}
