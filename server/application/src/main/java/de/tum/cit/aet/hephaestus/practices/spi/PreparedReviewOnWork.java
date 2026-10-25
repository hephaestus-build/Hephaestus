package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The review on the work that a review prepared: its summary, its placed notes and the composition trace it retains,
 * which holds the support of each copy and the quiet decisions.
 */
public record PreparedReviewOnWork(@Nullable String summary, List<Note> notes, FeedbackCompositionTrace trace) {
    public PreparedReviewOnWork {
        notes = List.copyOf(notes);
    }

    /** One placed note, at its copy position. */
    public record Note(int position, String body, FeedbackAnchor anchor) {}
}
