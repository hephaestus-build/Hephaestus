package de.tum.cit.aet.hephaestus.practices.result;

import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.resultBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustodyQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ResultCaptureTimesTest extends BaseUnitTest {
    final UUID job = UUID.randomUUID();
    final Result result =
            Result.builder().id(UUID.randomUUID()).workspaceId(1).itemId(2).build();
    final ReviewItemRepository assignments = mock(ReviewItemRepository.class);
    final ResultCaptureTimes captureTimes = new ResultCaptureTimes(assignments, mock(HistoryCustodyQuery.class));

    @Test
    void shouldNameOnlyTheJobOfTheOwnAssignmentThatRecordedExactlyThisResultWhenReadingProducers() {
        var produced = resultBuilder(result)
                .id(UUID.randomUUID())
                .reviewId(UUID.randomUUID())
                .build();
        var copied = resultBuilder(result)
                .id(UUID.randomUUID())
                .reviewId(UUID.randomUUID())
                .build();
        var legacy = resultBuilder(result)
                .id(UUID.randomUUID())
                .reviewId(UUID.randomUUID())
                .evidenceVersion((short) 0)
                .build();
        var jobless = resultBuilder(result)
                .id(UUID.randomUUID())
                .reviewId(UUID.randomUUID())
                .build();
        var foreign = resultBuilder(result)
                .id(UUID.randomUUID())
                .reviewId(UUID.randomUUID())
                .workspaceId(2)
                .build();
        var dependency = UUID.randomUUID();
        when(assignments.findByWorkspaceIdAndReviewIdIn(
                        1,
                        List.of(
                                produced.getReviewId(),
                                copied.getReviewId(),
                                legacy.getReviewId(),
                                jobless.getReviewId())))
                .thenReturn(List.of(
                        assignment(produced, produced.getPracticeId(), produced.getId(), job),
                        // The same review's assignment of another practice records this result with another job.
                        assignment(produced, produced.getPracticeId() + 1, produced.getId(), dependency),
                        // The own assignment records the result that this review copied, not this one.
                        assignment(copied, copied.getPracticeId(), UUID.randomUUID(), dependency),
                        ReviewItem.builder()
                                .workspaceId(1)
                                .reviewId(legacy.getReviewId())
                                .practiceId(legacy.getPracticeId())
                                .itemId(2)
                                .state(ReviewItemState.WITHHELD)
                                .reason(ReviewItem.LEGACY_AMBIGUOUS_RESULTS)
                                .jobId(dependency)
                                .build(),
                        assignment(jobless, jobless.getPracticeId(), jobless.getId(), null)));

        assertThat(captureTimes.producingJobs(
                        1, List.of(produced.facts(), copied.facts(), legacy.facts(), jobless.facts(), foreign.facts())))
                .isEqualTo(Map.of(produced.getId(), job));
        verify(assignments).findByWorkspaceIdAndReviewIdIn(eq(1L), any());
    }

    private static ReviewItem assignment(
            Result result, long practiceId, @Nullable UUID recordedResult, @Nullable UUID producingJob) {
        return ReviewItem.builder()
                .workspaceId(result.getWorkspaceId())
                .reviewId(result.getReviewId())
                .practiceId(practiceId)
                .itemId(result.getItemId())
                .state(ReviewItemState.JUDGED)
                .resultId(recordedResult)
                .jobId(producingJob)
                .build();
    }
}
