package de.tum.cit.aet.hephaestus.practices.trace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeSignalDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeRunDTO;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeRunStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.PracticeCoverageOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.PracticeReadinessOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.ReviewOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.ReviewRunState;
import de.tum.cit.aet.hephaestus.practices.trace.TraceInputs.PracticeOutput;
import de.tum.cit.aet.hephaestus.practices.trace.TraceInputs.SignalOccurrence;
import de.tum.cit.aet.hephaestus.practices.trace.TraceInputs.TracedPractice;
import de.tum.cit.aet.hephaestus.practices.trace.dto.PracticeTraceEntryDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PracticeTraceDeriverTest extends BaseUnitTest {

    private static final PracticeSignalDTO READY =
            new PracticeSignalDTO(ScmSignals.PULL_REQUEST_READY, "Marked ready for review");
    private static final PracticeSignalDTO MERGED = new PracticeSignalDTO(ScmSignals.PULL_REQUEST_MERGED, "Merged");
    private static final Instant AT = Instant.parse("2026-08-07T14:02:00Z");
    private static final UUID RUN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OCCURRENCE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Nested
    @DisplayName("A measurement was taken")
    class Measured {

        @Test
        void reportsAPracticeThatProducedObservationsAsReviewed() {
            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, completed()),
                    Map.of(1L, new PracticeOutput(2, 1, List.of(), RUN, AT)));

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.REVIEWED);
            assertThat(entry.observationCount()).isEqualTo(2);
            assertThat(entry.deliveredCount()).isEqualTo(1);
            assertThat(entry.reviewId()).isEqualTo(RUN);
        }

        /**
         * A later push review that left this practice out, because the Ready review had already answered it on the
         * same code, records it ready with no coverage of its own. The trace names the review whose observation
         * answers it, not the later run and not an unreached practice.
         */
        @Test
        void attributesAPracticeALaterReviewLeftOutToTheReviewThatAnsweredIt() {
            var synchronizedSignal = new PracticeSignalDTO(ScmSignals.PULL_REQUEST_SYNCHRONIZED, "Pushed");
            UUID push = UUID.fromString("33333333-3333-3333-3333-333333333333");
            var leftOut = completed(Map.of("slug", new PracticeReadinessOutcome(true, List.of(), null, null)));

            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY, synchronizedSignal),
                    List.of(
                            triggered(READY, RUN),
                            new SignalOccurrence(
                                    UUID.fromString("44444444-4444-4444-4444-444444444444"),
                                    synchronizedSignal,
                                    AT.plusSeconds(600),
                                    SignalState.TRIGGERED,
                                    null,
                                    push)),
                    Map.of(RUN, completed(), push, leftOut),
                    Map.of(1L, new PracticeOutput(1, 1, List.of(), RUN, AT)));

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.REVIEWED);
            assertThat(entry.reviewId()).isEqualTo(RUN);
            assertThat(entry.observationCount()).isOne();
        }

        /**
         * The same push review named on its own: only its occurrence is offered and it observed nothing for the
         * practice. Its recorded reuse names the earlier review as the answer and keeps its own zero counts, instead
         * of reading as unreached or as a clean assessment of its own.
         */
        @Test
        void namesTheAnsweringReviewWhenTheNamedReviewReusedItsAnswer() {
            UUID push = UUID.fromString("33333333-3333-3333-3333-333333333333");
            var reused = new ReviewOutcome(
                    ReviewRunState.COMPLETED,
                    false,
                    AT.plusSeconds(600),
                    Map.of("slug", new PracticeReadinessOutcome(true, List.of(), null, null)),
                    Map.of(),
                    Map.of("slug", RUN),
                    Map.of());

            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, push)),
                    Map.of(push, reused),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.REVIEWED);
            assertThat(entry.reviewId()).isEqualTo(RUN);
            assertThat(entry.occasionedById()).isEqualTo(OCCURRENCE);
            assertThat(entry.decidedAt()).isEqualTo(AT.plusSeconds(600));
            assertThat(entry.observationCount()).isZero();
            assertThat(entry.deliveredCount()).isZero();
            assertThat(entry.explanation())
                    .contains("earlier review", "did not assess it again")
                    .doesNotContain("nothing to report");
        }

        @Test
        void shouldCarryThePrecomputeRunOfTheReviewTheEntryNames() {
            var run = new PrecomputeRunDTO(PrecomputeRunStatus.SKIPPED, 0, List.of());
            var review = new ReviewOutcome(
                    ReviewRunState.COMPLETED,
                    false,
                    AT,
                    Map.of("slug", new PracticeReadinessOutcome(true, List.of(), null, null)),
                    Map.of("slug", PracticeCoverageOutcome.EVALUATED),
                    Map.of(),
                    Map.of("slug", run, "other-practice", new PrecomputeRunDTO(PrecomputeRunStatus.OK, 4, List.of())));

            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, review),
                    Map.of());

            assertThat(entry.reviewId()).isEqualTo(RUN);
            assertThat(entry.precompute()).isEqualTo(run);
        }

        @Test
        void shouldCarryNoPrecomputeRunWhenTheReviewReportedNoneForThePractice() {
            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, completed(Map.of("slug", new PracticeReadinessOutcome(true, List.of(), null, null)))),
                    Map.of());

            assertThat(entry.reviewId()).isEqualTo(RUN);
            assertThat(entry.precompute()).isNull();
        }

        @Test
        void letsPastMeasurementsOutrankATierTurnedOffSince() {
            var entry = only(
                    practice(PracticeAutonomy.OFF, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, completed()),
                    Map.of(1L, new PracticeOutput(1, 0, List.of(), RUN, AT)));

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.REVIEWED);
            assertThat(entry.autonomy()).isEqualTo(PracticeAutonomy.OFF);
        }

        @Test
        void shouldReportAnExplicitlyEvaluatedPracticeWithoutObservationsAsReviewed() {
            var review = new ReviewOutcome(
                    ReviewRunState.COMPLETED,
                    false,
                    AT,
                    Map.of("slug", new PracticeReadinessOutcome(true, List.of(), null, null)),
                    Map.of("slug", PracticeCoverageOutcome.EVALUATED));
            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, review),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.REVIEWED);
            assertThat(entry.explanation()).contains("found nothing to report");
            assertThat(entry.observationCount()).isZero();
        }

        @Test
        @DisplayName("a review still under way is not an all-clear, however ready the practice was")
        void doesNotReportAnAllClearWhileTheReviewIsStillRunning() {
            var running = new ReviewOutcome(
                    ReviewRunState.IN_PROGRESS,
                    false,
                    AT,
                    Map.of("slug", new PracticeReadinessOutcome(true, List.of(), null, null)),
                    Map.of());

            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, running),
                    Map.of());

            // Readiness is written before the sandbox starts, so it says nothing about what was assessed.
            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.RUNNING);
        }

        @Test
        @DisplayName("a review that died is not an all-clear either")
        void doesNotReportAnAllClearForAReviewThatFailed() {
            var failed = new ReviewOutcome(
                    ReviewRunState.FAILED,
                    false,
                    AT,
                    Map.of("slug", new PracticeReadinessOutcome(true, List.of(), null, null)),
                    Map.of());

            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, failed),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.FAILED);
            assertThat(entry.explanation()).contains("did not finish");
        }

        @Test
        void keepsMeasurementAndDeliveryOnSeparateAxes() {
            var entry = only(
                    practice(PracticeAutonomy.HUMAN_APPROVAL, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, completed()),
                    Map.of(
                            1L,
                            new PracticeOutput(
                                    3, 0, List.of(FeedbackSuppressionReason.PRACTICE_REQUIRES_APPROVAL), RUN, AT)));

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.REVIEWED);
            assertThat(entry.observationCount()).isEqualTo(3);
            assertThat(entry.deliveredCount()).isZero();
            assertThat(entry.withheldReasons()).containsExactly(FeedbackSuppressionReason.PRACTICE_REQUIRES_APPROVAL);
        }
    }

    @Nested
    @DisplayName("The run could not look")
    class NotAssessable {

        @Test
        void rendersARefusedReadinessDecisionAsNotAssessableWithItsBlockers() {
            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(
                            RUN,
                            completed(Map.of(
                                    "slug",
                                    new PracticeReadinessOutcome(
                                            false,
                                            List.of(
                                                    "The review captured only part of “Code changes”.",
                                                    "The review did not capture “Review threads”."),
                                            null,
                                            null)))),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.NOT_ASSESSABLE);
            assertThat(entry.explanation())
                    .isEqualTo(
                            "The review could not read the evidence this practice needs. "
                                    + "The review captured only part of “Code changes”. The review did not capture “Review threads”.");
        }

        /**
         * A practice that declares it is not reviewed automatically was stopped by policy, and nothing was
         * missing: calling it not assessable would send somebody to fix a capture that worked.
         */
        @Test
        void rendersADeclaredLimitationAsSkippedAndNeverAsMissingEvidence() {
            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(
                            RUN,
                            completed(Map.of(
                                    "slug",
                                    new PracticeReadinessOutcome(
                                            false,
                                            List.of(),
                                            "This practice needs human review, so no automatic review covers it.",
                                            null)))),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.SKIPPED);
            assertThat(entry.explanation())
                    .isEqualTo("This practice needs human review, so no automatic review covers it.")
                    .doesNotContain("could not read", "captured");
            assertThat(entry.reviewId()).isEqualTo(RUN);
        }

        @Test
        void rendersAnAbsentSubjectAsSkippedInThePracticeAuthorsOwnWords() {
            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(
                            RUN,
                            completed(Map.of(
                                    "slug",
                                    new PracticeReadinessOutcome(
                                            false,
                                            List.of(),
                                            null,
                                            "the change touches no dependency manifest or lockfile")))),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.SKIPPED);
            assertThat(entry.explanation())
                    .isEqualTo(
                            "This practice does not apply here: the change touches no dependency manifest or lockfile.");
            assertThat(entry.reviewId()).isEqualTo(RUN);
        }

        @Test
        void reportsARunRefusedForEvidenceAsNotAssessableEvenWithoutPerPracticeDetail() {
            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, new ReviewOutcome(ReviewRunState.COMPLETED, true, AT, Map.of(), Map.of())),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.NOT_ASSESSABLE);
        }
    }

    @Nested
    @DisplayName("Completed run decisions")
    class NotAdmitted {

        @Test
        void distinguishesAnEligiblePracticeTheBudgetDidNotReach() {
            var review = new ReviewOutcome(
                    ReviewRunState.COMPLETED,
                    false,
                    AT,
                    Map.of("slug", new PracticeReadinessOutcome(true, List.of(), null, null)),
                    Map.of("slug", PracticeCoverageOutcome.NOT_REACHED));

            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, review),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.NOT_REACHED);
            assertThat(entry.explanation()).contains("ended before it reached");
        }

        @Test
        void shouldNotReportAnAllClearWhenACompletedReviewHasNoCoverageRecord() {
            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, completed(Map.of("slug", new PracticeReadinessOutcome(true, List.of(), null, null)))),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.NOT_REACHED);
            assertThat(entry.explanation()).isEqualTo("The review did not record whether it reached this practice.");
            assertThat(entry.observationCount()).isZero();
        }

        @Test
        void reportsAPracticeTheRunConsideredOthersInsteadOfAsSkipped() {
            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, completed(Map.of("other", new PracticeReadinessOutcome(true, List.of(), null, null)))),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.SKIPPED);
            assertThat(entry.explanation()).isEqualTo("The review that ran did not include this practice.");
        }

        @Test
        void saysSoWhenTheRunRecordedNoReadinessAtAll() {
            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, completed()),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.SKIPPED);
            assertThat(entry.explanation()).contains("did not record what it decided");
        }
    }

    @Nested
    @DisplayName("The ledger's own answer")
    class LedgerStates {

        @Test
        void turnsARetryableRefusalIntoPendingWithTheActionThatLiftsIt() {
            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(refused(READY, SignalState.PENDING, SignalStateReason.BUDGET_EXHAUSTED)),
                    Map.of(),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.PENDING);
            assertThat(entry.explanation()).isEqualTo(SignalStateReason.BUDGET_EXHAUSTED.describe());
            assertThat(entry.occasionedBy()).isEqualTo(READY);
            assertThat(entry.occasionedById())
                    .as("a row must be able to point at the occurrence it rests on, not merely name the signal")
                    .isEqualTo(OCCURRENCE);
        }

        @Test
        void turnsATerminalRefusalIntoSkippedWithItsReason() {
            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(refused(READY, SignalState.SUPPRESSED, SignalStateReason.OUT_OF_REVIEW_SCOPE)),
                    Map.of(),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.SKIPPED);
            assertThat(entry.explanation()).isEqualTo(SignalStateReason.OUT_OF_REVIEW_SCOPE.describe());
        }

        @Test
        void reportsARetiredSignalAsLapsed() {
            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(refused(READY, SignalState.LAPSED, SignalStateReason.PENDING_DEADLINE_EXCEEDED)),
                    Map.of(),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.LAPSED);
        }

        @Test
        void reportsAnUndecidedSignalAsPending() {
            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(new SignalOccurrence(OCCURRENCE, READY, AT, SignalState.RECORDED, null, null)),
                    Map.of(),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.PENDING);
        }

        @Test
        void reportsAnUnfinishedRunAsRunningAndAFailedOneAsFailed() {
            var running = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, new ReviewOutcome(ReviewRunState.IN_PROGRESS, false, null, Map.of(), Map.of())),
                    Map.of());
            var failed = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, new ReviewOutcome(ReviewRunState.FAILED, false, AT, Map.of(), Map.of())),
                    Map.of());

            assertThat(running.outcome()).isEqualTo(PracticeTraceOutcome.RUNNING);
            assertThat(failed.outcome()).isEqualTo(PracticeTraceOutcome.FAILED);
        }

        @Test
        void ignoresOccurrencesThePracticeDoesNotWatch() {
            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, MERGED),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, completed()),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.NOT_OCCASIONED);
            assertThat(entry.occasionedBy()).isNull();
        }
    }

    @Nested
    @DisplayName("Nothing happened")
    class Quiet {

        @Test
        void reportsAPracticeTurnedOffAsSilencedRatherThanQuiet() {
            var entry = only(practice(PracticeAutonomy.OFF, READY), List.of(triggered(READY, RUN)), Map.of(), Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.TURNED_OFF);
        }

        @Test
        void reportsAPracticeNothingConnectedCanRaiseAsDormantWithItsReason() {
            var practice = new TracedPractice(
                    1L,
                    "slug",
                    "A practice",
                    null,
                    null,
                    PracticeAutonomy.AUTOMATIC,
                    List.of(READY),
                    "Nothing connected to this workspace reports the moments this practice watches for.");
            var entry = only(practice, List.of(), Map.of(), Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.DORMANT);
            assertThat(entry.explanation())
                    .isEqualTo("Nothing connected to this workspace reports the moments this practice watches for.");
        }

        @Test
        void reportsAnUnoccasionedPracticeAsSuch() {
            var entry = only(practice(PracticeAutonomy.AUTOMATIC, MERGED), List.of(), Map.of(), Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.NOT_OCCASIONED);
        }
    }

    @Test
    @DisplayName("every explanation a practice can get is written in words, never in identifiers")
    void shouldExplainEveryOutcomeWithoutARawIdentifier() {
        List<PracticeTraceEntryDTO> entries = new ArrayList<>();
        for (SignalStateReason reason : SignalStateReason.values()) {
            entries.add(only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(refused(READY, reason.resultingState(), reason)),
                    Map.of(),
                    Map.of()));
        }
        for (SignalState state : SignalState.values()) {
            entries.add(only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(refused(READY, state, null)),
                    Map.of(),
                    Map.of()));
        }
        entries.add(only(practice(PracticeAutonomy.AUTOMATIC, MERGED), List.of(), Map.of(), Map.of()));
        entries.add(only(practice(PracticeAutonomy.OFF, READY), List.of(), Map.of(), Map.of()));
        entries.add(only(
                practice(PracticeAutonomy.AUTOMATIC, READY),
                List.of(triggered(READY, RUN)),
                Map.of(RUN, completed()),
                Map.of(1L, new PracticeOutput(1, 0, List.of(), RUN, AT))));
        entries.add(only(
                practice(PracticeAutonomy.AUTOMATIC, READY),
                List.of(triggered(READY, RUN)),
                Map.of(RUN, completed()),
                Map.of()));
        entries.add(only(
                practice(PracticeAutonomy.AUTOMATIC, READY),
                List.of(triggered(READY, RUN)),
                Map.of(RUN, new ReviewOutcome(ReviewRunState.IN_PROGRESS, false, null, Map.of(), Map.of())),
                Map.of()));

        assertThat(entries)
                .allSatisfy(entry -> assertThat(entry.explanation())
                        .doesNotContain("scm.", "artifact", "ledger", "signal", "binding", "gate", "measur", "_"));
    }

    @Test
    void shouldCarryEachWatchedSignalAndTheOccasionWithTheWordsAReaderSees() {
        var entry = only(
                practice(PracticeAutonomy.AUTOMATIC, READY, MERGED),
                List.of(refused(READY, SignalState.PENDING, SignalStateReason.BUDGET_EXHAUSTED)),
                Map.of(),
                Map.of());

        assertThat(entry.watches())
                .extracting(PracticeSignalDTO::displayName)
                .containsExactly("Marked ready for review", "Merged");
        assertThat(entry.occasionedBy())
                .isNotNull()
                .extracting(PracticeSignalDTO::displayName)
                .isEqualTo("Marked ready for review");
    }

    @Test
    void ordersInformativeAnswersFirst() {
        var reviewed =
                new TracedPractice(1L, "b-reviewed", "B", null, null, PracticeAutonomy.AUTOMATIC, List.of(READY), null);
        var quiet =
                new TracedPractice(2L, "a-quiet", "A", null, null, PracticeAutonomy.AUTOMATIC, List.of(MERGED), null);
        var off = new TracedPractice(3L, "c-off", "C", null, null, PracticeAutonomy.OFF, List.of(READY), null);

        var entries = PracticeTraceDeriver.derive(
                List.of(quiet, off, reviewed),
                List.of(triggered(READY, RUN)),
                Map.of(RUN, completed()),
                Map.of(1L, new PracticeOutput(1, 0, List.of(), RUN, AT)));

        assertThat(entries)
                .extracting(PracticeTraceEntryDTO::practiceSlug)
                .containsExactly("b-reviewed", "c-off", "a-quiet");
    }

    private static PracticeTraceEntryDTO only(
            TracedPractice practice,
            List<SignalOccurrence> occurrences,
            Map<UUID, ReviewOutcome> reviews,
            Map<Long, PracticeOutput> outputs) {
        List<PracticeTraceEntryDTO> entries =
                PracticeTraceDeriver.derive(List.of(practice), occurrences, reviews, outputs);
        assertThat(entries).hasSize(1);
        return entries.getFirst();
    }

    private static TracedPractice practice(PracticeAutonomy autonomy, PracticeSignalDTO... watches) {
        return new TracedPractice(1L, "slug", "A practice", null, null, autonomy, List.of(watches), null);
    }

    private static SignalOccurrence triggered(PracticeSignalDTO signal, UUID reviewId) {
        return new SignalOccurrence(OCCURRENCE, signal, AT, SignalState.TRIGGERED, null, reviewId);
    }

    private static SignalOccurrence refused(
            PracticeSignalDTO signal, SignalState state, @Nullable SignalStateReason reason) {
        return new SignalOccurrence(OCCURRENCE, signal, AT, state, reason, null);
    }

    private static ReviewOutcome completed() {
        return completed(Map.of());
    }

    private static ReviewOutcome completed(Map<String, PracticeReadinessOutcome> readiness) {
        return new ReviewOutcome(ReviewRunState.COMPLETED, false, AT, readiness, Map.of());
    }
}
