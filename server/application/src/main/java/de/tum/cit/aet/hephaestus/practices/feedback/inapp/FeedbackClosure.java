package de.tum.cit.aet.hephaestus.practices.feedback.inapp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How a piece of in-app feedback stopped being open: resolved by the work or by the developer, or closed
 * because its practice changed its review rules, whichever came first
 * ({@code docs/contributor/practice-review-glossary.mdx} § How feedback resolves).
 */
public record FeedbackClosure(Instant at, ClosedBy by) {

    /** Declared in the order a tie goes: the work, then the developer, then the practice. */
    public enum ClosedBy {
        /** {@code WorkResolution.CLEAN_NEEDED} pieces of the developer's work in a row came back clean. */
        WORK,
        /** The developer's own answer resolved it. */
        DEVELOPER,
        /** The practice's review rules changed after the evidence was measured; nothing resolved it. */
        PRACTICE_CHANGED,
    }

    /** The first of the three that happened, or {@code null} while none has. */
    public static @Nullable FeedbackClosure of(
            @Nullable Instant resolvedByWorkAt,
            @Nullable Instant resolvedByDeveloperAt,
            @Nullable Instant practiceChangedAt) {
        List<FeedbackClosure> happened = new ArrayList<>(3);
        if (resolvedByWorkAt != null) {
            happened.add(new FeedbackClosure(resolvedByWorkAt, ClosedBy.WORK));
        }
        if (resolvedByDeveloperAt != null) {
            happened.add(new FeedbackClosure(resolvedByDeveloperAt, ClosedBy.DEVELOPER));
        }
        if (practiceChangedAt != null) {
            happened.add(new FeedbackClosure(practiceChangedAt, ClosedBy.PRACTICE_CHANGED));
        }
        return happened.stream()
                .min(Comparator.comparing(FeedbackClosure::at).thenComparing(FeedbackClosure::by))
                .orElse(null);
    }
}
