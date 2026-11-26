package de.tum.cit.aet.hephaestus.practices.feedback.response;

import java.time.Duration;
import java.time.Instant;

/**
 * ADR 0125 decision 3: a workspace admin reads a developer's explanation of a disagreement only as a correction, for 30
 * days after the developer gave it. After that, the admin still sees that the developer disagrees and when, but not
 * their words.
 */
public final class CorrectionWindow {
    public static final Duration LENGTH = Duration.ofDays(30);

    private CorrectionWindow() {}

    /** Whether an explanation given at {@code explainedAt} is still a correction that an admin may read at {@code now}. */
    public static boolean open(Instant explainedAt, Instant now) {
        return explainedAt.isAfter(now.minus(LENGTH));
    }
}
