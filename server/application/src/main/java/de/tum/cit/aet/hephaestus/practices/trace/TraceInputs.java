package de.tum.cit.aet.hephaestus.practices.trace;

import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeSignalDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The facts a trace is derived from, flattened out of their entities into plain values so
 * {@link PracticeTraceDeriver}, which holds every judgement in this feature, can be exercised without a
 * database, a Spring context or a mirror.
 */
final class TraceInputs {

    private TraceInputs() {}

    /**
     * @param watches        the signals it watches, each with the words a reader sees for it
     * @param dormancyReason why nothing connected here can raise what it watches, or {@code null} when
     *                      something can; the sentence comes straight from {@code DormantBinding}
     */
    record TracedPractice(
            Long id,
            String slug,
            String name,
            @Nullable String groupSlug,
            @Nullable String groupName,
            PracticeAutonomy autonomy,
            List<PracticeSignalDTO> watches,
            @Nullable String dormancyReason) {}

    /**
     * @param id carried because a signal name alone cannot identify which occurrence an answer rests on:
     *           the same signal recurs on every new revision
     */
    record SignalOccurrence(
            UUID id,
            PracticeSignalDTO signal,
            Instant occurredAt,
            SignalState state,
            @Nullable SignalStateReason stateReason,
            @Nullable UUID reviewId) {}

    /** What one practice produced on this artifact, across every run of it. */
    record PracticeOutput(
            int observations,
            int delivered,
            List<FeedbackSuppressionReason> withheldReasons,
            @Nullable UUID latestReviewId,
            @Nullable Instant latestObservedAt) {
        static final PracticeOutput NONE = new PracticeOutput(0, 0, List.of(), null, null);
    }
}
