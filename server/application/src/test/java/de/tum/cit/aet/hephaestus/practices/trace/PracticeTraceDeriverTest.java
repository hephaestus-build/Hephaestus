package de.tum.cit.aet.hephaestus.practices.trace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PracticeTraceDeriverTest extends BaseUnitTest {

    private static final SignalName READY = ScmSignals.PULL_REQUEST_READY;
    private static final SignalName MERGED = ScmSignals.PULL_REQUEST_MERGED;
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
                    Map.of("slug", new PracticeReadinessOutcome(true, List.of(), null)),
                    Map.of("slug", PracticeCoverageOutcome.EVALUATED));
            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, review),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.REVIEWED);
            assertThat(entry.explanation()).contains("nothing to report");
            assertThat(entry.observationCount()).isZero();
        }

        @Test
        @DisplayName("a review still under way is not an all-clear, however ready the practice was")
        void doesNotReportAnAllClearWhileTheReviewIsStillRunning() {
            var running = new ReviewOutcome(
                    ReviewRunState.IN_PROGRESS,
                    false,
                    AT,
                    Map.of("slug", new PracticeReadinessOutcome(true, List.of(), null)),
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
                    Map.of("slug", new PracticeReadinessOutcome(true, List.of(), null)),
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
                                            false, List.of("scm.pull-request.diff was captured only in part"), null)))),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.NOT_ASSESSABLE);
            assertThat(entry.explanation()).contains("scm.pull-request.diff was captured only in part");
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
                    Map.of("slug", new PracticeReadinessOutcome(true, List.of(), null)),
                    Map.of("slug", PracticeCoverageOutcome.NOT_REACHED));

            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, review),
                    Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.NOT_REACHED);
            assertThat(entry.explanation()).contains("ended before reaching");
        }

        @Test
        void shouldNotReportAnAllClearWhenACompletedReviewHasNoCoverageRecord() {
            var entry = only(
                    practice(PracticeAutonomy.AUTOMATIC, READY),
                    List.of(triggered(READY, RUN)),
                    Map.of(RUN, completed(Map.of("slug", new PracticeReadinessOutcome(true, List.of(), null)))),
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
                    Map.of(RUN, completed(Map.of("other", new PracticeReadinessOutcome(true, List.of(), null)))),
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
            assertThat(entry.explanation()).contains("budget refills");
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
            assertThat(entry.explanation()).contains("branches and repositories");
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
                    PracticeAutonomy.AUTOMATIC,
                    List.of(READY),
                    "no connected integration raises [scm.pull_request.ready]; connect one of [GITHUB]");
            var entry = only(practice, List.of(), Map.of(), Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.DORMANT);
            assertThat(entry.explanation()).startsWith("No connected integration raises");
        }

        @Test
        void reportsAnUnoccasionedPracticeAsSuch() {
            var entry = only(practice(PracticeAutonomy.AUTOMATIC, MERGED), List.of(), Map.of(), Map.of());

            assertThat(entry.outcome()).isEqualTo(PracticeTraceOutcome.NOT_OCCASIONED);
        }
    }

    @Test
    void ordersInformativeAnswersFirst() {
        var reviewed = new TracedPractice(1L, "b-reviewed", "B", PracticeAutonomy.AUTOMATIC, List.of(READY), null);
        var quiet = new TracedPractice(2L, "a-quiet", "A", PracticeAutonomy.AUTOMATIC, List.of(MERGED), null);
        var off = new TracedPractice(3L, "c-off", "C", PracticeAutonomy.OFF, List.of(READY), null);

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

    private static TracedPractice practice(PracticeAutonomy autonomy, SignalName... watches) {
        return new TracedPractice(1L, "slug", "A practice", autonomy, List.of(watches), null);
    }

    private static SignalOccurrence triggered(SignalName signal, UUID reviewId) {
        return new SignalOccurrence(OCCURRENCE, signal, AT, SignalState.TRIGGERED, null, reviewId);
    }

    private static SignalOccurrence refused(SignalName signal, SignalState state, SignalStateReason reason) {
        return new SignalOccurrence(OCCURRENCE, signal, AT, state, reason, null);
    }

    private static ReviewOutcome completed() {
        return completed(Map.of());
    }

    private static ReviewOutcome completed(Map<String, PracticeReadinessOutcome> readiness) {
        return new ReviewOutcome(ReviewRunState.COMPLETED, false, AT, readiness, Map.of());
    }
}
