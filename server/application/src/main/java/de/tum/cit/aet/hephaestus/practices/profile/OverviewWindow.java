package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The span the overview measures change over: after {@code since}, at or before {@code until}.
 *
 * <p>Resolved once and read by every rule, so "in the window" means one thing on the whole page. It opens at
 * the run before the latest one and closes now; with no such run it spans the standing's own look-back, since
 * a standing "before" further back than that has no evidence to be read off.
 *
 * @param since the open lower edge — the moment the window opens after
 * @param until the closed upper edge — the moment the profile is read as of
 */
public record OverviewWindow(Instant since, Instant until) {

    /** The span a window with no previous run to open at falls back to. */
    public static final Duration LOOKBACK = Duration.ofDays(PracticeStandingService.LOOKBACK_DAYS);

    public OverviewWindow {
        if (!since.isBefore(until)) {
            throw new IllegalArgumentException("since must be before until");
        }
    }

    /**
     * @param previousRunAt when the run before the latest one was recorded, or null when there is none
     * @param now the moment the profile is read as of
     */
    public static OverviewWindow sincePreviousRun(@Nullable Instant previousRunAt, Instant now) {
        Instant horizon = now.minus(LOOKBACK);
        // A previous run older than the look-back has no standing left to read; one that is not before now
        // (recorded in the same instant the profile is read) cannot open a window.
        Instant since = previousRunAt != null && previousRunAt.isAfter(horizon) && previousRunAt.isBefore(now)
                ? previousRunAt
                : horizon;
        return new OverviewWindow(since, now);
    }

    public boolean contains(Instant at) {
        return at.isAfter(since) && !at.isAfter(until);
    }
}
