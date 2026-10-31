package de.tum.cit.aet.hephaestus.agent.job;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueue;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequested;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReviewJobEventListenerTest extends BaseUnitTest {
    @Test
    void shouldEnqueueOnlyEligibleFrozenAssignments() {
        var reviews = mock(ReviewService.class);
        var queue = mock(ReviewQueue.class);
        var assignments = mock(ReviewAssignmentQuery.class);
        var assignment = RuntimeReviewTestData.assignment(5L, null);
        var review = RuntimeReviewTestData.review(ReviewKind.ITEM, assignment);
        when(reviews.find(1, review.id())).thenReturn(Optional.of(review));
        when(assignments.executable(1, review.id(), 2)).thenReturn(List.of(assignment.id()));
        new ReviewJobEventListener(reviews, queue, assignments).onReviewRequested(new ReviewRequested(review.id(), 1));
        verify(queue).enqueue(review, 2, List.of(assignment.id()));
        // The parent stays QUEUED until a worker claims its job.
        verify(reviews, never()).start(1, review.id());
    }

    @Test
    void shouldLeaveScanEnumerationToItsDriver() {
        var reviews = mock(ReviewService.class);
        var queue = mock(ReviewQueue.class);
        var assignments = mock(ReviewAssignmentQuery.class);
        var review = RuntimeReviewTestData.review(ReviewKind.SCAN, RuntimeReviewTestData.assignment(null, null));
        when(reviews.find(1, review.id())).thenReturn(Optional.of(review));
        new ReviewJobEventListener(reviews, queue, assignments).onReviewRequested(new ReviewRequested(review.id(), 1));
        verifyNoInteractions(queue, assignments);
    }
}
