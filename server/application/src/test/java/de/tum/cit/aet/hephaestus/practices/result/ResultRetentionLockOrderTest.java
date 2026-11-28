package de.tum.cit.aet.hephaestus.practices.result;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.FeedbackRetentionProperties;
import de.tum.cit.aet.hephaestus.practices.advice.AdviceRedaction;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyCleanupCustody;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.PracticeTrialProperties;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceStaleCause;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Retention chooses the results it deletes only under FOR UPDATE. The JPA write lock (FOR NO KEY UPDATE) lets a new
 * feedback citation in, so a result could then stay while the advice written from it lost its text.
 */
class ResultRetentionLockOrderTest extends BaseUnitTest {
    private static final long WORKSPACE = 7L;

    @Test
    void shouldLockTheCandidatesForUpdateBeforeChoosingTheSetThatItRedactsAndDeletes() {
        var reviews = mock(ReviewRepository.class);
        var results = mock(ResultRepository.class);
        var feedback = mock(FeedbackRepository.class);
        var advice = mock(AdviceRedaction.class);
        UUID candidate = UUID.randomUUID();
        UUID review = UUID.randomUUID();
        var result = mock(Result.class);
        when(result.getReviewId()).thenReturn(review);
        when(feedback.expiredIds(eq(WORKSPACE), any(), anyInt())).thenReturn(List.of());
        when(reviews.expiredScanIds(eq(WORKSPACE), any())).thenReturn(List.of());
        when(results.expiredUnreferencedIds(eq(WORKSPACE), any(), anyInt())).thenReturn(List.of(candidate));
        when(results.findByWorkspaceIdAndIdIn(WORKSPACE, List.of(candidate))).thenReturn(List.of(result));
        when(reviews.lockByIdAndWorkspaceId(review, WORKSPACE)).thenReturn(Optional.of(mock(Review.class)));
        when(results.lockInIdOrder(eq(WORKSPACE), any())).thenReturn(List.of(candidate));
        when(results.withoutFeedbackBasis(eq(WORKSPACE), any())).thenReturn(List.of(candidate));
        var service = new ResultRetentionService(
                reviews,
                results,
                feedback,
                mock(FeedbackCopyCleanupCustody.class),
                mock(ScanReportService.class),
                advice,
                new FeedbackRetentionProperties(Duration.ofDays(400)),
                new PracticeTrialProperties(Duration.ofDays(30), 1),
                mock(PlatformTransactionManager.class),
                Clock.systemUTC());

        service.purgeWorkspace(WORKSPACE, Instant.now());

        var order = inOrder(results, advice);
        order.verify(results).lockInIdOrder(eq(WORKSPACE), any());
        order.verify(results).withoutFeedbackBasis(eq(WORKSPACE), any());
        order.verify(advice).beforeResultsDeleted(WORKSPACE, List.of(candidate), AdviceStaleCause.RETENTION);
        order.verify(results).deleteWithoutFeedbackBasis(eq(WORKSPACE), any());
    }
}
