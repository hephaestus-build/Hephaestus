package de.tum.cit.aet.hephaestus.practices.trace;

import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.PracticeCoverageOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.PracticeReadinessOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.ReviewOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.ReviewRunState;
import de.tum.cit.aet.hephaestus.practices.trace.TraceInputs.PracticeOutput;
import de.tum.cit.aet.hephaestus.practices.trace.TraceInputs.SignalOccurrence;
import de.tum.cit.aet.hephaestus.practices.trace.TraceInputs.TracedPractice;
import de.tum.cit.aet.hephaestus.practices.trace.dto.PracticeTraceEntryDTO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Derives one trace outcome per practice. Historical observations outrank current configuration, and
 * recorded run decisions outrank inferred state.
 */
final class PracticeTraceDeriver {

    private PracticeTraceDeriver() {}

    private static final Map<PracticeTraceOutcome, Integer> RANK = Map.ofEntries(
            Map.entry(PracticeTraceOutcome.REVIEWED, 0),
            Map.entry(PracticeTraceOutcome.NOT_ASSESSABLE, 1),
            Map.entry(PracticeTraceOutcome.RUNNING, 2),
            Map.entry(PracticeTraceOutcome.PENDING, 3),
            Map.entry(PracticeTraceOutcome.FAILED, 4),
            Map.entry(PracticeTraceOutcome.LAPSED, 5),
            Map.entry(PracticeTraceOutcome.NOT_REACHED, 6),
            Map.entry(PracticeTraceOutcome.SKIPPED, 7),
            Map.entry(PracticeTraceOutcome.DORMANT, 8),
            Map.entry(PracticeTraceOutcome.TURNED_OFF, 9),
            Map.entry(PracticeTraceOutcome.NOT_OCCASIONED, 10));

    static List<PracticeTraceEntryDTO> derive(
            List<TracedPractice> practices,
            List<SignalOccurrence> occurrences,
            Map<UUID, ReviewOutcome> reviews,
            Map<Long, PracticeOutput> outputsByPracticeId) {
        List<PracticeTraceEntryDTO> entries = new ArrayList<>(practices.size());
        for (TracedPractice practice : practices) {
            entries.add(derive(
                    practice,
                    occurrences,
                    reviews,
                    outputsByPracticeId.getOrDefault(practice.id(), PracticeOutput.NONE)));
        }
        return entries.stream()
                .sorted(Comparator.comparingInt((PracticeTraceEntryDTO entry) -> RANK.getOrDefault(entry.outcome(), 99))
                        .thenComparing(PracticeTraceEntryDTO::practiceName)
                        .thenComparing(PracticeTraceEntryDTO::practiceSlug))
                .toList();
    }

    private static PracticeTraceEntryDTO derive(
            TracedPractice practice,
            List<SignalOccurrence> occurrences,
            Map<UUID, ReviewOutcome> reviews,
            PracticeOutput output) {
        // Newest first: when several occurrences match, the most recent one is the answer somebody wants.
        List<SignalOccurrence> matched = occurrences.stream()
                .filter(occurrence -> practice.watches().contains(occurrence.signal()))
                .sorted(Comparator.comparing(SignalOccurrence::occurredAt).reversed())
                .toList();
        SignalOccurrence latest = matched.isEmpty() ? null : matched.getFirst();

        if (output.observations() > 0) {
            // Attribute observations to the occurrence that started their review, not a newer matching signal.
            SignalOccurrence occasion = matched.stream()
                    .filter(occurrence -> Objects.equals(occurrence.reviewId(), output.latestReviewId()))
                    .findFirst()
                    .orElse(latest);
            return entry(
                    practice,
                    PracticeTraceOutcome.REVIEWED,
                    "Assessed on this artifact.",
                    occasion,
                    output.latestObservedAt(),
                    output.latestReviewId(),
                    output);
        }

        for (SignalOccurrence occurrence : matched) {
            ReviewOutcome review = occurrence.reviewId() == null ? null : reviews.get(occurrence.reviewId());
            if (review == null) {
                continue;
            }
            // Readiness is recorded before the sandbox starts, so a review that is still running or that
            // never finished carries one too. Answering from it would tell a developer their practice was
            // assessed and clean while the review was still under way, or after it had died — the run's
            // own state is the only thing that makes an all-clear a statement about their work.
            if (review.state() != ReviewRunState.COMPLETED) {
                continue;
            }
            PracticeReadinessOutcome readiness =
                    review.readinessByPracticeSlug().get(practice.slug());
            if (readiness == null) {
                continue;
            }
            if (readiness.ready()
                    && review.coverageByPracticeSlug().get(practice.slug()) == PracticeCoverageOutcome.NOT_REACHED) {
                return entry(
                        practice,
                        PracticeTraceOutcome.NOT_REACHED,
                        "The review ended before reaching this practice.",
                        occurrence,
                        review.decidedAt(),
                        occurrence.reviewId(),
                        output);
            }
            if (readiness.ready()) {
                return entry(
                        practice,
                        PracticeTraceOutcome.REVIEWED,
                        "Assessed on this artifact; nothing to report.",
                        occurrence,
                        review.decidedAt(),
                        occurrence.reviewId(),
                        output);
            }
            // SKIPPED, not NOT_ASSESSABLE. The run read the evidence and the thing this practice judges
            // was not in the work — "we chose not to ask", for a reason that will not change for this
            // artifact, which is exactly what SKIPPED means here. Calling it NOT_ASSESSABLE would report
            // an instrument failure that did not happen and invite somebody to go fixing our capture.
            if (readiness.notApplicable() != null) {
                return entry(
                        practice,
                        PracticeTraceOutcome.SKIPPED,
                        "This practice does not apply here: " + readiness.notApplicable() + ".",
                        occurrence,
                        review.decidedAt(),
                        occurrence.reviewId(),
                        output);
            }
            return entry(
                    practice,
                    PracticeTraceOutcome.NOT_ASSESSABLE,
                    notAssessable(readiness),
                    occurrence,
                    review.decidedAt(),
                    occurrence.reviewId(),
                    output);
        }

        // 3. The workspace's own choice, before any mechanical reason.
        if (practice.autonomy() == PracticeAutonomy.OFF) {
            return entry(
                    practice,
                    PracticeTraceOutcome.TURNED_OFF,
                    "This workspace turned the practice off, so it is not measured here.",
                    latest,
                    null,
                    null,
                    output);
        }

        // 4. Something it watches happened; the ledger says what became of it.
        if (latest != null) {
            return fromOccurrence(practice, latest, reviews, output);
        }

        // 5. Nothing it watches can happen here at all.
        if (practice.dormancyReason() != null) {
            return entry(
                    practice,
                    PracticeTraceOutcome.DORMANT,
                    capitalize(practice.dormancyReason()),
                    null,
                    null,
                    null,
                    output);
        }

        return entry(
                practice,
                PracticeTraceOutcome.NOT_OCCASIONED,
                "Nothing this practice watches has happened to this artifact.",
                null,
                null,
                null,
                output);
    }

    private static PracticeTraceEntryDTO fromOccurrence(
            TracedPractice practice,
            SignalOccurrence occurrence,
            Map<UUID, ReviewOutcome> reviews,
            PracticeOutput output) {
        ReviewOutcome review = occurrence.reviewId() == null ? null : reviews.get(occurrence.reviewId());
        if (review != null) {
            return switch (review.state()) {
                case IN_PROGRESS ->
                    entry(
                            practice,
                            PracticeTraceOutcome.RUNNING,
                            "A review of this artifact is under way.",
                            occurrence,
                            null,
                            occurrence.reviewId(),
                            output);
                case FAILED ->
                    entry(
                            practice,
                            PracticeTraceOutcome.FAILED,
                            "The review carrying this practice did not finish.",
                            occurrence,
                            review.decidedAt(),
                            occurrence.reviewId(),
                            output);
                case COMPLETED -> completed(practice, occurrence, review, output);
            };
        }
        return switch (occurrence.state()) {
            case PENDING ->
                entry(
                        practice,
                        PracticeTraceOutcome.PENDING,
                        reasonCopy(occurrence.stateReason(), "Recorded and waiting to be re-offered."),
                        occurrence,
                        null,
                        null,
                        output);
            case SUPPRESSED ->
                entry(
                        practice,
                        PracticeTraceOutcome.SKIPPED,
                        reasonCopy(occurrence.stateReason(), "Recorded and deliberately not reviewed."),
                        occurrence,
                        null,
                        null,
                        output);
            case LAPSED ->
                entry(
                        practice,
                        PracticeTraceOutcome.LAPSED,
                        reasonCopy(occurrence.stateReason(), "Retired unreviewed after waiting too long."),
                        occurrence,
                        null,
                        null,
                        output);
            // RECORDED, or TRIGGERED with a review we can no longer read: seen, not yet ruled on.
            default ->
                entry(
                        practice,
                        PracticeTraceOutcome.PENDING,
                        "Recorded; no decision has been taken on it yet.",
                        occurrence,
                        null,
                        null,
                        output);
        };
    }

    /**
     * A run that finished and never named this practice — three distinguishable facts: refused for
     * evidence, ran but did not admit this practice, or recorded no decisions at all.
     */
    private static PracticeTraceEntryDTO completed(
            TracedPractice practice, SignalOccurrence occurrence, ReviewOutcome review, PracticeOutput output) {
        if (review.insufficientEvidence()) {
            return entry(
                    practice,
                    PracticeTraceOutcome.NOT_ASSESSABLE,
                    "The review could not read the evidence it needed, so nothing was measured.",
                    occurrence,
                    review.decidedAt(),
                    occurrence.reviewId(),
                    output);
        }
        if (review.readinessByPracticeSlug().isEmpty()) {
            return entry(
                    practice,
                    PracticeTraceOutcome.SKIPPED,
                    "A review ran on this artifact and did not record what it decided about this practice.",
                    occurrence,
                    review.decidedAt(),
                    occurrence.reviewId(),
                    output);
        }
        return entry(
                practice,
                PracticeTraceOutcome.SKIPPED,
                "The review that ran did not include this practice.",
                occurrence,
                review.decidedAt(),
                occurrence.reviewId(),
                output);
    }

    private static PracticeTraceEntryDTO entry(
            TracedPractice practice,
            PracticeTraceOutcome outcome,
            String explanation,
            @Nullable SignalOccurrence occasion,
            @Nullable Instant decidedAt,
            @Nullable UUID reviewId,
            PracticeOutput output) {
        return new PracticeTraceEntryDTO(
                practice.slug(),
                practice.name(),
                practice.autonomy(),
                outcome,
                explanation,
                practice.watches(),
                occasion == null ? null : occasion.signal(),
                occasion == null ? null : occasion.id(),
                decidedAt,
                reviewId,
                output.observations(),
                output.delivered(),
                output.withheldReasons());
    }

    private static String notAssessable(PracticeReadinessOutcome readiness) {
        if (readiness.blockers().isEmpty()) {
            return "The review could not read what this practice needs.";
        }
        return "The review could not read what this practice needs: " + String.join("; ", readiness.blockers()) + ".";
    }

    /**
     * One sentence per recorded reason, phrased as what would change the answer — the reason's own,
     * so this page and every other surface that explains a silence say the same thing about it.
     */
    private static String reasonCopy(@Nullable SignalStateReason reason, String fallback) {
        return reason == null ? fallback : reason.describe();
    }

    /**
     * Sentence-cases a reason built elsewhere. {@code Character.toUpperCase} rather than
     * {@code String.toUpperCase}, which is locale-sensitive (the Turkish dotless i) and banned by
     * {@code LocaleSafetyArchTest}.
     */
    private static String capitalize(String sentence) {
        if (sentence.isEmpty()) {
            return sentence;
        }
        return Character.toUpperCase(sentence.charAt(0)) + sentence.substring(1);
    }
}
