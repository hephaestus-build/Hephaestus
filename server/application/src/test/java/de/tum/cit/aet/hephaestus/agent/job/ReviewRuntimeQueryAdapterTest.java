package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunHold;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingFact;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingFacts;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingFactsQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingStage;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingSums;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewRuntimeQueryAdapterTest extends BaseUnitTest {
    private static final long WORKSPACE = 7;
    private static final Instant CREATED = Instant.parse("2026-10-20T00:00:00Z");
    private static final Instant FROM = CREATED.minusSeconds(60);
    private static final Instant TO = CREATED.plusSeconds(2000);
    private final ReviewRuntimeRepository runtime = mock(ReviewRuntimeRepository.class);
    private final ReviewTimingFactsQuery facts = mock(ReviewTimingFactsQuery.class);
    private final ReviewRuntimeQueryAdapter adapter = new ReviewRuntimeQueryAdapter(
            mock(ReviewJobRepository.class), mock(ReviewAssignmentQuery.class), runtime, facts);

    @Test
    void shouldExcludeUnknownIntervalsWhenPublicCompletionPredatesTheRunningContinuation() {
        var review = UUID.randomUUID();
        var timeline = new ReviewTimingFact(
                review,
                ReviewOrigin.EVENT,
                CREATED,
                CREATED.minusSeconds(10),
                null,
                CREATED.plusSeconds(800),
                CREATED.plusSeconds(810),
                CREATED.plusSeconds(850),
                null,
                false,
                false);
        var current = new ReviewRuntimeRepository.TimingJob(
                review,
                ReviewJobStatus.RUNNING,
                1,
                CREATED.plusSeconds(900),
                CREATED.plusSeconds(902),
                CREATED.plusSeconds(904),
                null,
                null);

        var result = timing(List.of(timeline), List.of(current), new ReviewTimingFacts(10, 0, 0, 0, 0));

        assertThat(result.stages()).doesNotContainKeys("QUEUE", "PREPARATION", "MODEL", "RUN_UNSPLIT");
        assertThat(result.stages())
                .containsEntry("ASKED", new ReviewTimingStage(1, 10.0, 10.0))
                .containsEntry("TOTAL", new ReviewTimingStage(1, 820.0, 820.0))
                .containsEntry("POSTED", new ReviewTimingStage(1, 10.0, 10.0))
                .containsEntry("READ", new ReviewTimingStage(1, 50.0, 50.0));
        assertThat(result.total()).isNull();
    }

    @Test
    void shouldKeepFinishedElapsedFactsWithoutInventingACompleteRetryBreakdown() {
        var review = UUID.randomUUID();
        var timeline = new ReviewTimingFact(
                review,
                ReviewOrigin.EVENT,
                CREATED,
                CREATED.minusSeconds(10),
                CREATED.plusSeconds(940),
                CREATED.plusSeconds(800),
                CREATED.plusSeconds(810),
                CREATED.plusSeconds(850),
                null,
                true,
                false);
        var current = new ReviewRuntimeRepository.TimingJob(
                review,
                ReviewJobStatus.COMPLETED,
                1,
                CREATED.plusSeconds(900),
                CREATED.plusSeconds(902),
                CREATED.plusSeconds(904),
                CREATED.plusSeconds(930),
                null);

        var result = timing(List.of(timeline), List.of(current), new ReviewTimingFacts(10, 10, 2, 1, 3));

        assertThat(result.stages()).doesNotContainKeys("QUEUE", "PREPARATION", "MODEL", "RUN_UNSPLIT");
        assertThat(result.stages())
                .containsEntry("FINISHED", new ReviewTimingStage(1, 950.0, 950.0))
                .containsEntry("TOTAL", new ReviewTimingStage(1, 820.0, 820.0))
                .containsEntry("POSTED", new ReviewTimingStage(1, 10.0, 10.0))
                .containsEntry("READ", new ReviewTimingStage(1, 50.0, 50.0));
        assertThat(result.total()).isNull();
        assertThat(result.finished()).isNull();
        assertThat(result.finishedAfterMerge()).isEqualTo(2);
        assertThat(result.finishedAfterMergeStartedByMerge()).isOne();
        assertThat(result.feedbackWithheldAsMerged()).isEqualTo(3);
    }

    @Test
    void shouldKeepMeasuredFirstAttemptStagesAndBothCompleteBreakdowns() {
        var review = UUID.randomUUID();
        var row = new ReviewRuntimeRepository.TimingJob(
                review,
                ReviewJobStatus.COMPLETED,
                0,
                CREATED,
                CREATED.plusSeconds(5),
                CREATED.plusSeconds(8),
                CREATED.plusSeconds(20),
                null);

        var result = timing(List.of(finished(review)), List.of(row), new ReviewTimingFacts(2, 2, 0, 0, 0));

        assertThat(result.stages())
                .containsEntry("QUEUE", new ReviewTimingStage(1, 5.0, 5.0))
                .containsEntry("PREPARATION", new ReviewTimingStage(1, 3.0, 3.0))
                .containsEntry("MODEL", new ReviewTimingStage(1, 12.0, 12.0))
                .containsEntry("FINISHED", new ReviewTimingStage(1, 24.0, 24.0));
        assertThat(result.total()).isEqualTo(new ReviewTimingSums(2, 5, 3, 7, 0, 2.0));
        assertThat(result.finished()).isEqualTo(new ReviewTimingSums(2, 5, 3, 12, 0, 2.0));
    }

    @Test
    void shouldKeepFirstAttemptRuntimeUnsplitWhenExecutionStartWasNotRecorded() {
        var review = UUID.randomUUID();
        var row = new ReviewRuntimeRepository.TimingJob(
                review,
                ReviewJobStatus.COMPLETED,
                0,
                CREATED,
                CREATED.plusSeconds(5),
                null,
                CREATED.plusSeconds(20),
                null);

        var result = timing(List.of(finished(review)), List.of(row), new ReviewTimingFacts(2, 2, 0, 0, 0));

        assertThat(result.stages()).containsEntry("RUN_UNSPLIT", new ReviewTimingStage(1, 15.0, 15.0));
        assertThat(result.stages()).doesNotContainKeys("PREPARATION", "MODEL");
        assertThat(result.total()).isEqualTo(new ReviewTimingSums(2, 5, 0, 0, 10, 2.0));
        assertThat(result.finished()).isEqualTo(new ReviewTimingSums(2, 5, 0, 0, 15, 2.0));
    }

    @Test
    void shouldKeepCurrentLongWaitCountsForQueuedRetriesWithoutTimingTheirUnknownPast() {
        var review = UUID.randomUUID();
        var timeline = new ReviewTimingFact(
                review, ReviewOrigin.REQUEST, CREATED, null, null, null, null, null, null, false, false);
        var waiting = new ReviewRuntimeRepository.TimingJob(
                review, ReviewJobStatus.QUEUED, 1, CREATED, null, null, null, null);
        var budgetHeld = new ReviewRuntimeRepository.TimingJob(
                review, ReviewJobStatus.QUEUED, 1, CREATED, null, null, null, AgentRunHold.BUDGET.name());

        var result = timing(List.of(timeline), List.of(waiting, budgetHeld), new ReviewTimingFacts(0, 0, 0, 0, 0));

        assertThat(result.waitingLong()).isOne();
        assertThat(result.stages()).doesNotContainKeys("QUEUE", "PREPARATION", "MODEL", "RUN_UNSPLIT");
    }

    @Test
    void shouldKeepUnaffectedPopulationsCompleteWhenAnotherReviewIsStillContinuing() {
        var complete = UUID.randomUUID();
        var continuing = UUID.randomUUID();
        var incomplete = new ReviewTimingFact(
                continuing, ReviewOrigin.REQUEST, CREATED, null, null, null, null, null, null, false, false);
        var first = new ReviewRuntimeRepository.TimingJob(
                complete,
                ReviewJobStatus.COMPLETED,
                0,
                CREATED,
                CREATED.plusSeconds(5),
                CREATED.plusSeconds(8),
                CREATED.plusSeconds(20),
                null);
        var retry = new ReviewRuntimeRepository.TimingJob(
                continuing,
                ReviewJobStatus.RUNNING,
                1,
                CREATED.plusSeconds(900),
                CREATED.plusSeconds(902),
                CREATED.plusSeconds(904),
                null,
                null);

        var result = timing(
                List.of(finished(complete), incomplete), List.of(first, retry), new ReviewTimingFacts(2, 2, 0, 0, 0));

        assertThat(result.stages())
                .containsEntry("QUEUE", new ReviewTimingStage(1, 5.0, 5.0))
                .containsEntry("MODEL", new ReviewTimingStage(1, 12.0, 12.0));
        assertThat(result.total()).isEqualTo(new ReviewTimingSums(2, 5, 3, 7, 0, 2.0));
        assertThat(result.finished()).isEqualTo(new ReviewTimingSums(2, 5, 3, 12, 0, 2.0));
    }

    private ReviewTimingSnapshot timing(
            List<ReviewTimingFact> timelines,
            List<ReviewRuntimeRepository.TimingJob> rows,
            ReviewTimingFacts aggregate) {
        var ids = timelines.stream().map(ReviewTimingFact::reviewId).toList();
        when(facts.window(WORKSPACE, FROM, TO)).thenReturn(timelines);
        when(runtime.timingJobs(WORKSPACE, ids)).thenReturn(rows);
        when(facts.facts(WORKSPACE, ids, FROM, TO)).thenReturn(aggregate);
        return adapter.timing(WORKSPACE, FROM, TO, CREATED.plusSeconds(600));
    }

    private static ReviewTimingFact finished(UUID review) {
        return new ReviewTimingFact(
                review,
                ReviewOrigin.EVENT,
                CREATED,
                CREATED.minusSeconds(2),
                CREATED.plusSeconds(22),
                CREATED.plusSeconds(15),
                CREATED.plusSeconds(17),
                CREATED.plusSeconds(19),
                null,
                true,
                false);
    }
}
