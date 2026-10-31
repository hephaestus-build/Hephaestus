package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompletion;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewWriteGuard;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ReviewJobCompletionServiceTest extends BaseUnitTest {
    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
    private final ReviewJobAssignments assignments = mock(ReviewJobAssignments.class);
    private final ReviewAssignmentStore members = mock(ReviewAssignmentStore.class);
    private final ReviewService reviews = mock(ReviewService.class);

    private ReviewJobCompletionService service(ReviewJob job, ReviewKind kind) {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var candidate = mock(ReviewJobRepository.CompletionCandidate.class);
        when(candidate.getId()).thenReturn(job.getId());
        when(candidate.getWorkspaceId()).thenReturn(1L);
        when(candidate.getReviewId()).thenReturn(job.getReviewId());
        when(jobs.findTerminalForProcessing(100)).thenReturn(List.of(candidate));
        when(jobs.findByIdAndWorkspaceIdForUpdate(job.getId(), 1)).thenReturn(Optional.of(job));
        var assignment = RuntimeReviewTestData.assignment(null, null);
        when(assignments.review(job)).thenReturn(RuntimeReviewTestData.review(kind, assignment));
        return new ReviewJobCompletionService(
                jobs,
                mock(ReviewWriteGuard.class),
                assignments,
                members,
                reviews,
                mock(ReviewJobLifecycleService.class),
                new TransactionTemplate(manager),
                Clock.systemUTC());
    }

    @Test
    void terminalScanProcessingSettlesBeforeTheDriverClosesTheProductReview() {
        var job = RuntimeReviewTestData.job();
        job.setStatus(ReviewJobStatus.COMPLETED);
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("proof");
        service(job, ReviewKind.SCAN).tick();
        assertThat(job.getProcessingStatus()).isEqualTo(ProcessingStatus.DONE);
        verify(jobs).saveAndFlush(job);
        verifyNoInteractions(members, reviews);
    }

    @Test
    void aCancelledUnstartedScanDoesNotClaimItsItemsWereReviewed() {
        var job = RuntimeReviewTestData.job();
        job.setStatus(ReviewJobStatus.CANCELLED);
        service(job, ReviewKind.SCAN).tick();
        assertThat(job.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
        verifyNoInteractions(members, reviews);
    }

    @Test
    void anActualFailedScanInvocationWithholdsItsReadyMembership() {
        var job = RuntimeReviewTestData.job();
        job.setStatus(ReviewJobStatus.FAILED);
        job.setStartedAt(Instant.now());
        job.setExecutionStartedAt(Instant.now());
        var service = service(job, ReviewKind.SCAN);
        var assignment = RuntimeReviewTestData.assignment(null, null);
        service.tick();
        verify(members)
                .fail(
                        1,
                        assignment.id(),
                        ReviewStopReason.EXECUTION_FAILED,
                        "The execution ended without an admitted result");
        assertThat(job.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
        verifyNoInteractions(reviews);
    }

    @Test
    void shouldCloseAnAdmittedPartialRunWithItsRuntimeNonResultReason() {
        var job = RuntimeReviewTestData.job();
        job.setStatus(ReviewJobStatus.COMPLETED);
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("proof");
        var capture = new JsonMapper().createObjectNode();
        capture.putArray("nonResults")
                .addObject()
                .put("practiceSlug", "clear-names")
                .put("reason", "BUDGET");
        job.setCapture(capture);
        var service = service(job, ReviewKind.ITEM);
        var assignment = RuntimeReviewTestData.assignment(null, null);
        when(assignments.load(job)).thenReturn(List.of(assignment));
        service.tick();
        verify(members)
                .fail(
                        1,
                        assignment.id(),
                        ReviewStopReason.BUDGET,
                        "Runtime execution ended without a result for this practice");
        var completion = ArgumentCaptor.forClass(ReviewCompletion.class);
        verify(reviews).complete(ArgumentMatchers.eq(1L), ArgumentMatchers.eq(job.getReviewId()), completion.capture());
        assertThat(completion.getValue().status()).isEqualTo(ReviewStatus.PARTIAL);
        assertThat(completion.getValue().reason()).isEqualTo(ReviewStopReason.EXECUTION_INCOMPLETE);
    }

    @Test
    void capturedRefusalClosesAsNotReviewedWithItsReadinessReason() {
        var job = RuntimeReviewTestData.job();
        job.setStatus(ReviewJobStatus.COMPLETED);
        job.setOutput(new JsonMapper().createObjectNode().put("outcome", "INSUFFICIENT_EVIDENCE"));
        service(job, ReviewKind.ITEM).tick();
        var completion = ArgumentCaptor.forClass(ReviewCompletion.class);
        verify(reviews).complete(ArgumentMatchers.eq(1L), ArgumentMatchers.eq(job.getReviewId()), completion.capture());
        assertThat(completion.getValue().status()).isEqualTo(ReviewStatus.PARTIAL);
        assertThat(completion.getValue().reason()).isEqualTo(ReviewStopReason.READINESS_REFUSED);
    }
}
