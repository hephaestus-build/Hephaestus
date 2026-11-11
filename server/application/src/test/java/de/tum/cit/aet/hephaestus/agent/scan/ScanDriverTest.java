package de.tum.cit.aet.hephaestus.agent.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobStateConflictException;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompletion;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueue;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewScanReuse;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewScanStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ScanExecutionSnapshot;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ScanDriverTest extends BaseUnitTest {
    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    private final ReviewScanStore scans = mock(ReviewScanStore.class);
    private final ReviewScanReuse reuse = mock(ReviewScanReuse.class);
    private final ReviewService reviews = mock(ReviewService.class);
    private final ScanService inventory = mock(ScanService.class);
    private final ScanBudget budget = mock(ScanBudget.class);
    private final ReviewQueue queue = mock(ReviewQueue.class);
    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
    private final ReviewJobLifecycleService lifecycle = mock(ReviewJobLifecycleService.class);
    private final ReviewAssignmentSnapshot assignment = RuntimeReviewTestData.assignment(null, null);
    private final ReviewSnapshot review = RuntimeReviewTestData.review(ReviewKind.SCAN, assignment);
    private final ScanDriver driver = new ScanDriver(
            scans,
            reuse,
            reviews,
            mock(ReviewStart.class),
            inventory,
            budget,
            queue,
            jobs,
            lifecycle,
            mock(TransactionTemplate.class),
            new ScanProperties(100, 10000, Duration.ofMinutes(1), Duration.ofHours(1)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setup() {
        when(jobs.findActiveId(any(), anyLong(), any())).thenReturn(Optional.empty());
        when(scans.pending(anyLong(), any(), anyInt())).thenReturn(List.of(assignment));
    }

    private ScanExecutionSnapshot state(long listed, long judged, long reused, long withheld, boolean cancel) {
        return new ScanExecutionSnapshot(
                review,
                ReviewStatus.RUNNING,
                BigDecimal.TEN,
                NOW.minusSeconds(30),
                cancel,
                true,
                true,
                Set.of(31L),
                listed,
                judged,
                reused,
                withheld);
    }

    @Test
    void waitsForTheActiveInvocation() {
        when(jobs.findActiveId(any(), anyLong(), any()))
                .thenReturn(Optional.of(RuntimeReviewTestData.job().getId()));
        driver.advance(state(1, 0, 0, 0, false));
        verifyNoInteractions(budget, queue, scans, reviews);
    }

    @Test
    void waitsForTerminalProcessingBeforeReusingOrClosing() {
        when(jobs.hasUnsettledInvocation(1, review.id())).thenReturn(true);
        driver.advance(state(1, 0, 0, 0, false));
        verifyNoInteractions(budget, queue, scans, reviews);
    }

    @Test
    void closesWithBudgetReasonWithoutStartingAFile() {
        driver.advance(state(1, 0, 0, 0, false));
        assertCompletion(ReviewStatus.PARTIAL, ReviewStopReason.BUDGET);
        verifyNoInteractions(queue);
    }

    @Test
    void queuesOnlyOneFileThroughTheExistingQueue() {
        var state = state(1, 0, 0, 0, false);
        when(budget.permits(state, List.of(assignment))).thenReturn(true);
        driver.advance(state);
        verify(queue).enqueue(review, assignment.item().itemId(), List.of(assignment.id()));
        verifyNoInteractions(reviews);
    }

    @Test
    void aWithheldMemberCannotCloseAsComplete() {
        var state = state(1, 0, 0, 1, false);
        when(scans.pending(anyLong(), any(), anyInt())).thenReturn(List.of());
        when(scans.lock(1, review.id())).thenReturn(state);
        driver.advance(state);
        assertCompletion(ReviewStatus.PARTIAL, ReviewStopReason.RESULT_WITHHELD);
    }

    @Test
    void aFullyReusedScanClosesWithoutCallingAModel() {
        var state = state(1, 0, 1, 0, false);
        when(scans.pending(anyLong(), any(), anyInt())).thenReturn(List.of());
        when(scans.lock(1, review.id())).thenReturn(state);
        driver.advance(state);
        assertCompletion(ReviewStatus.COMPLETE, null);
        verifyNoInteractions(budget, queue);
    }

    @Test
    void shouldCompleteWhenEveryInventoriedFileIsAnExplicitExclusion() {
        var state = state(0, 0, 0, 0, false);
        when(scans.pending(anyLong(), any(), anyInt())).thenReturn(List.of());
        when(scans.lock(1, review.id())).thenReturn(state);
        driver.advance(state);
        assertCompletion(ReviewStatus.COMPLETE, null);
        verifyNoInteractions(budget, queue);
    }

    @Test
    void cancellationWaitsForItsRunningJob() {
        var job = RuntimeReviewTestData.job();
        when(jobs.findActiveId(any(), anyLong(), any())).thenReturn(Optional.of(job.getId()));
        driver.advance(state(1, 0, 0, 0, true));
        verify(lifecycle).cancel(1L, job.getId());
        verifyNoInteractions(reviews, queue);
    }

    @Test
    void cancellationWithoutAnInvocationClosesImmediately() {
        driver.advance(state(1, 0, 0, 0, true));
        assertCompletion(ReviewStatus.CANCELLED, ReviewStopReason.CANCELLED);
    }

    private void assertCompletion(ReviewStatus status, @Nullable ReviewStopReason reason) {
        var capture = ArgumentCaptor.forClass(ReviewCompletion.class);
        verify(reviews).complete(ArgumentMatchers.eq(1L), ArgumentMatchers.eq(review.id()), capture.capture());
        assertThat(capture.getValue().status()).isEqualTo(status);
        assertThat(capture.getValue().reason()).isEqualTo(reason);
    }

    @Test
    void expiredScansStopBeforeStartingAnotherFile() {
        var expired = new ScanExecutionSnapshot(
                review,
                ReviewStatus.RUNNING,
                BigDecimal.TEN,
                NOW.minusSeconds(3600),
                false,
                true,
                true,
                Set.of(31L),
                1,
                0,
                0,
                0);
        driver.advance(expired);
        assertCompletion(ReviewStatus.PARTIAL, ReviewStopReason.DEADLINE);
        verifyNoInteractions(budget, queue);
    }

    @Test
    void changedJudgmentOptionsCannotRunUnderTheOldMethodDigest() {
        var state = state(1, 0, 0, 0, false);
        when(budget.permits(state, List.of(assignment)))
                .thenThrow(new ReviewJobStateConflictException("The judgment options changed"));
        driver.advance(state);
        assertCompletion(ReviewStatus.PARTIAL, ReviewStopReason.MODEL_CHANGED);
        verifyNoInteractions(queue);
    }

    @Test
    void shouldStopAnUnavailableModelWithItsOwnReason() {
        var state = state(1, 0, 0, 0, false);
        when(budget.permits(state, List.of(assignment))).thenThrow(new ScanModelUnavailableException("Unavailable"));
        driver.advance(state);
        assertCompletion(ReviewStatus.PARTIAL, ReviewStopReason.MODEL_UNAVAILABLE);
        verifyNoInteractions(queue);
    }

    @Test
    void shouldNotMislabelAnUnexpectedFaultAsModelUnavailability() {
        var state = state(1, 0, 0, 0, false);
        when(budget.permits(state, List.of(assignment))).thenThrow(new IllegalStateException("Unexpected fault"));
        assertThatThrownBy(() -> driver.advance(state)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(reviews, queue);
    }

    @Test
    void shouldEnumerateOutsideTheLockedTransactionAndRecordAfterRelocking() {
        var inside = new AtomicBoolean();
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> {
            inside.set(true);
            return new SimpleTransactionStatus();
        });
        doAnswer(call -> {
                    inside.set(false);
                    return null;
                })
                .when(manager)
                .commit(any());
        var recorded = state(1, 0, 0, 0, false);
        var initial = new ScanExecutionSnapshot(
                review,
                ReviewStatus.QUEUED,
                BigDecimal.TEN,
                recorded.createdAt(),
                false,
                false,
                false,
                Set.of(),
                0,
                0,
                0,
                0);
        when(scans.active(anyInt())).thenReturn(List.of(initial));
        when(scans.lock(1, review.id())).thenReturn(initial, initial, recorded);
        when(reviews.start(1, review.id())).thenReturn(true);
        var prepared = new ScanService.PreparedInventory(List.of(), Map.of(), List.of());
        when(inventory.prepare(review)).thenAnswer(call -> {
            assertThat(inside).isFalse();
            return prepared;
        });
        doAnswer(call -> {
                    assertThat(inside).isFalse();
                    return null;
                })
                .when(reuse)
                .reuseListed(1, review.id(), recorded.createdAt().plus(Duration.ofHours(1)));
        doAnswer(call -> {
                    assertThat(inside).isTrue();
                    return null;
                })
                .when(inventory)
                .record(review, prepared);
        new ScanDriver(
                        scans,
                        reuse,
                        reviews,
                        mock(ReviewStart.class),
                        inventory,
                        budget,
                        queue,
                        jobs,
                        lifecycle,
                        new TransactionTemplate(manager),
                        new ScanProperties(100, 10000, Duration.ofMinutes(1), Duration.ofHours(1)),
                        Clock.fixed(NOW, ZoneOffset.UTC))
                .tick();
        verify(inventory).record(review, prepared);
        assertThat(inside).isFalse();
    }
}
