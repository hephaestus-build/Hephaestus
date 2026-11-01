package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.HistoryCopies;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewHistoryContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.handler.composition.PinnedInlineAnchorQuery;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasis;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasisRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SealResultsRequestDTO;
import de.tum.cit.aet.hephaestus.practices.spi.AdmissionReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultQuery;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionContext;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStore;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStagingStore;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedFeedback;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedPackage;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedReviewQuery;
import de.tum.cit.aet.hephaestus.practices.spi.PrivateFeedbackProgress;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewWriteGuard;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class ResultAdmissionServiceTest extends BaseUnitTest {
    private static final UUID LEASE = UUID.fromString("fafb277e-9c90-58ff-9167-1f1b3b0dce88");

    private final JsonMapper mapper = new JsonMapper();
    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
    private final ReviewOutputService output = mock(ReviewOutputService.class);
    private final AdmittedResultQuery proof = mock(AdmittedResultQuery.class);
    private final ReviewJobAssignments assignments = mock(ReviewJobAssignments.class);
    private final PublicReviewEligibility publicEligibility = mock(PublicReviewEligibility.class);
    private final JobEvidenceFiles files = mock(JobEvidenceFiles.class);
    private final ReviewCompositionBasisRepository bases = mock(ReviewCompositionBasisRepository.class);
    private final FeedbackStore feedback = mock(FeedbackStore.class);
    private final ReviewAssignmentStore members = mock(ReviewAssignmentStore.class);
    private final FeedbackCompositionService composition = new FeedbackCompositionService(
            feedback,
            mock(PreparedReviewQuery.class),
            members,
            mock(ResultHistoryQuery.class),
            mock(HistoryCopies.class),
            mock(FeedbackHistoryQuery.class));
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final ReviewJob job = RuntimeReviewTestData.job();
    private ResultAdmissionService service;

    @BeforeEach
    void setup() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient()
                .when(files.indexLateRead(any(), any(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        lenient()
                .when(feedback.compositionContext(anyLong(), any(), any()))
                .thenReturn(new FeedbackCompositionContext(null, null));
        lenient()
                .when(feedback.privateProgress(anyLong(), any(), anyLong(), any()))
                .thenReturn(PrivateFeedbackProgress.NONE);
        service = new ResultAdmissionService(
                jobs,
                proof,
                assignments,
                new AdmittedResultProjectionService(
                        assignments, publicEligibility, mock(PinnedInlineAnchorQuery.class), mapper),
                files,
                mock(ReviewHistoryContentSource.class),
                mapper,
                manager,
                composition,
                mock(ReviewWriteGuard.class),
                new PracticeResultStaging(
                        output,
                        mock(PracticeStagingStore.class),
                        members,
                        assignments,
                        files,
                        bases,
                        mapper,
                        new PracticeCatalogInjector(mapper)));
        job.setLeasedUntil(Instant.now().plusSeconds(3600));
        lenient().when(jobs.databaseClock()).thenAnswer(call -> Instant.now());
        lenient().when(members.endedPractices(anyLong(), any(), any())).thenReturn(Set.of(31L));
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setLeaseId(LEASE);
        job.setLeasedUntil(Instant.now().plus(Duration.ofMinutes(10)));
        job.setCapture(mapper.createObjectNode());
        when(jobs.findByIdAndWorkspaceId(job.getId(), 1L)).thenReturn(Optional.of(job));
        when(jobs.findByIdAndWorkspaceIdForUpdate(job.getId(), 1)).thenReturn(Optional.of(job));
        when(jobs.databaseClock()).thenReturn(Instant.now());
    }

    private ResultAdmissionService.AdmissionIdentity identity() {
        return new ResultAdmissionService.AdmissionIdentity(job.getId(), 1, 0, LEASE);
    }

    @Test
    void shouldAcceptALiveDatabaseLeaseWhenTheServerClockIsAhead() {
        var databaseNow = Instant.now().minusSeconds(7200);
        job.setLeasedUntil(databaseNow.plusSeconds(60));
        when(jobs.databaseClock()).thenReturn(databaseNow);

        assertThat(service.ownedJob(identity())).isSameAs(job);
    }

    @Test
    void shouldRejectALeaseAtItsDatabaseExpiryWhenTheServerClockIsBehind() {
        var databaseNow = Instant.now().plusSeconds(7200);
        job.setLeasedUntil(databaseNow);
        when(jobs.databaseClock()).thenReturn(databaseNow);

        assertThatThrownBy(() -> service.ownedJob(identity()))
                .isInstanceOf(ResultAdmissionService.StaleAttemptException.class);
    }

    @Test
    void shouldPrepareOneAtomicPackageAndReturnItsCanonicalReceipt() {
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("admitted");
        var assignment = RuntimeReviewTestData.assignment(5L, null);
        when(assignments.review(job)).thenReturn(RuntimeReviewTestData.review(ReviewKind.ITEM, assignment));
        UUID resultId = UUID.randomUUID();
        var candidate = mock(ResultCandidate.class);
        when(candidate.developerId()).thenReturn(5L);
        when(proof.forAttempt(1, job.getReviewId(), job.getId(), 0))
                .thenReturn(List.of(new AdmittedResultSnapshot(resultId, candidate, List.of())));
        UUID feedbackId = FeedbackIds.onWork(1, job.getReviewId(), 5);
        Instant preparedAt = Instant.parse("2026-10-14T06:00:00Z");
        when(feedback.prepareAll(eq(1L), eq(job.getReviewId()), any()))
                .thenReturn(new PreparedPackage(
                        "package-digest",
                        preparedAt,
                        List.of(new PreparedFeedback(feedbackId, "piece-digest", preparedAt))));
        var request = new PrepareFeedbackRequestDTO(
                job.getReviewId(),
                job.getId(),
                0,
                List.of(resultId),
                List.of(new ComposedFeedback(
                        FeedbackDestination.ON_WORK,
                        List.of(resultId),
                        "Use clear names.",
                        List.of(),
                        null,
                        new FeedbackCompositionTrace(
                                List.of(new FeedbackCompositionTrace.CopySupport(
                                        FeedbackCopyKind.SUMMARY,
                                        0,
                                        List.of(new FeedbackCompositionTrace.Support(resultId, null)))),
                                List.of(),
                                null,
                                null),
                        null)),
                true,
                null);
        var response = service.prepareFeedback(identity(), request);
        assertThat(response.feedbackIds()).containsExactly(feedbackId);
        assertThat(response.contentDigest()).isEqualTo("package-digest");
        assertThat(response.preparedAt()).isEqualTo(preparedAt);
        assertThat(job.getPublicReviewDigest()).isEqualTo("package-digest");
        verify(feedback).prepareAll(eq(1L), eq(job.getReviewId()), any());
        verify(members).publicReviewed(1, job.getReviewId(), job.getId(), 0, preparedAt);
    }

    @Test
    void shouldRejectAnOldWorkerAttemptBeforePreparingAnything() {
        var stale = new ResultAdmissionService.AdmissionIdentity(job.getId(), 1, 1, LEASE);
        assertThatThrownBy(() -> service.prepareFeedback(
                        stale,
                        new PrepareFeedbackRequestDTO(
                                job.getReviewId(), job.getId(), 1, List.of(), List.of(), false, null)))
                .isInstanceOf(ResultAdmissionService.StaleAttemptException.class);
        verifyNoInteractions(feedback, proof);
    }

    @Test
    void shouldKeepAdmissionIdempotentAndRejectChangedPayload() {
        var assignment = RuntimeReviewTestData.assignment(5L, null);
        when(assignments.review(job)).thenReturn(RuntimeReviewTestData.review(ReviewKind.ITEM, assignment));
        when(assignments.load(job)).thenReturn(List.of(assignment));
        when(proof.forAttempt(1, job.getReviewId(), job.getId(), 0)).thenReturn(List.of());
        when(publicEligibility.publicResultIds(job, List.of())).thenReturn(Set.of());
        var request = new SealResultsRequestDTO(3, job.getReviewId(), job.getId(), 0);
        var writes = new AtomicInteger();
        when(output.seal(job, List.of())).thenAnswer(call -> {
            writes.incrementAndGet();
            return new AdmissionReceipt(List.of(), List.of());
        });
        service.admit(identity(), request);
        service.admit(identity(), request);
        assertThat(writes.get()).isEqualTo(1);
        job.setAdmissionDigest("another-payload");
        assertThatThrownBy(() -> service.admit(identity(), request)).isInstanceOf(AdmissionConflictException.class);
    }

    @Test
    void shouldBoundRetainedWriterInputsByTheirOriginalOccasionDeadline() {
        var deadline = Instant.now().plusSeconds(3600);
        var request = admissionWithRetainedInputs(deadline);
        var copyId = UUID.randomUUID();
        when(files.completeReceipt(identity())).thenReturn(Optional.of(copyId));
        when(files.holdVouchingReceipt(identity(), copyId)).thenReturn(true);
        when(files.originalCompositionInputs(identity()))
                .thenReturn(
                        Optional.of(Map.of("context/original.txt", "Original work".getBytes(StandardCharsets.UTF_8))));

        service.admit(identity(), request);

        var saved = ArgumentCaptor.forClass(ReviewCompositionBasis.class);
        verify(bases).save(saved.capture());
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(deadline);
        assertThat(saved.getValue().getCopyId()).isEqualTo(copyId);
        assertThat(saved.getValue().getPayload().path("files").has("context/original.txt"))
                .isTrue();
    }

    @Test
    void shouldKeepNoNewWriterInputCopyAfterTheOccasionDeadline() {
        var request = admissionWithRetainedInputs(Instant.now().minusSeconds(1));

        service.admit(identity(), request);

        verify(bases, never()).save(any());
    }

    @Test
    void shouldKeepTheExistingWriterRetentionForWorkWithoutARetainedOccasion() {
        var request = admissionWithRetainedInputs(null);
        var copyId = UUID.randomUUID();
        when(files.completeReceipt(identity())).thenReturn(Optional.of(copyId));
        when(files.holdVouchingReceipt(identity(), copyId)).thenReturn(true);
        when(files.originalCompositionInputs(identity()))
                .thenReturn(
                        Optional.of(Map.of("context/current.txt", "Current work".getBytes(StandardCharsets.UTF_8))));
        var before = Instant.now();

        service.admit(identity(), request);

        var after = Instant.now();
        var saved = ArgumentCaptor.forClass(ReviewCompositionBasis.class);
        verify(bases).save(saved.capture());
        assertThat(saved.getValue().getExpiresAt())
                .isBetween(before.plus(ReviewCompositionBasis.RETENTION), after.plus(ReviewCompositionBasis.RETENTION));
    }

    private SealResultsRequestDTO admissionWithRetainedInputs(@Nullable Instant deadline) {
        var assignment = RuntimeReviewTestData.assignment(5L, null);
        var original = RuntimeReviewTestData.review(ReviewKind.ITEM, assignment);
        var review = deadline == null
                ? original
                : new ReviewSnapshot(
                        original.id(),
                        original.workspaceId(),
                        original.item(),
                        original.kind(),
                        original.version(),
                        ReviewOrigin.EVENT,
                        original.rolloutRevision(),
                        original.practices(),
                        UUID.randomUUID(),
                        deadline);
        when(assignments.review(job)).thenReturn(review);
        when(assignments.load(job)).thenReturn(List.of(assignment));
        when(proof.forAttempt(1, job.getReviewId(), job.getId(), 0)).thenReturn(List.of());
        when(publicEligibility.publicResultIds(job, List.of())).thenReturn(Set.of());
        var request = new SealResultsRequestDTO(3, job.getReviewId(), job.getId(), 0);
        when(output.seal(job, List.of())).thenReturn(new AdmissionReceipt(List.of(), List.of()));
        return request;
    }

    @Test
    void completedEmptyPublicCompositionRecordsAcceptanceWithoutInventingFeedback() {
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("admitted");
        when(assignments.review(job))
                .thenReturn(RuntimeReviewTestData.review(ReviewKind.ITEM, RuntimeReviewTestData.assignment(5L, null)));
        var acceptedAt = Instant.parse("2026-10-14T10:00:00Z");
        when(feedback.prepareAll(1, job.getReviewId(), List.of()))
                .thenReturn(new PreparedPackage("empty-package", acceptedAt, List.of()));
        var response = service.prepareFeedback(
                identity(),
                new PrepareFeedbackRequestDTO(job.getReviewId(), job.getId(), 0, List.of(), List.of(), true, null));
        assertThat(response.feedbackIds()).isEmpty();
        assertThat(job.getPublicReviewDigest()).isEqualTo("empty-package");
        verify(members).publicReviewed(1, job.getReviewId(), job.getId(), 0, acceptedAt);
    }

    @Test
    void privateOnlyPreparationDoesNotRecordPublicAcceptance() {
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("admitted");
        when(assignments.review(job))
                .thenReturn(RuntimeReviewTestData.review(ReviewKind.ITEM, RuntimeReviewTestData.assignment(5L, null)));
        var acceptedAt = Instant.parse("2026-10-14T10:00:00Z");
        when(feedback.prepareAll(1, job.getReviewId(), List.of()))
                .thenReturn(new PreparedPackage("empty-package", acceptedAt, List.of()));
        service.prepareFeedback(
                identity(),
                new PrepareFeedbackRequestDTO(job.getReviewId(), job.getId(), 0, List.of(), List.of(), false, null));
        assertThat(job.getPublicReviewDigest()).isNull();
        verifyNoInteractions(members);
    }

    @Test
    void shouldReadPrivateProgressPerDestinationForTheAdmittedResults() {
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("admitted");
        when(assignments.review(job))
                .thenReturn(RuntimeReviewTestData.review(ReviewKind.ITEM, RuntimeReviewTestData.assignment(5L, null)));
        UUID carded = UUID.randomUUID(), pending = UUID.randomUUID();
        var admitted =
                List.of(admitted(carded, 11, ResultOutcome.NOT_MET), admitted(pending, 12, ResultOutcome.NOT_MET));
        when(proof.forAttempt(1, job.getReviewId(), job.getId(), 0)).thenReturn(admitted);
        var progress = new PrivateFeedbackProgress(
                Set.of(carded), 1, 0, Map.of(FeedbackDestination.PRACTICE_PAGE, Set.of(carded)));
        when(feedback.privateProgress(1, job.getReviewId(), 5, List.of(carded, pending)))
                .thenReturn(progress);

        assertThat(service.privateProgress(job).decidedByDestination())
                .as("a page card decides its practice on the page only")
                .containsExactly(Map.entry(FeedbackDestination.PRACTICE_PAGE, Set.of(carded)));
    }

    private static AdmittedResultSnapshot admitted(UUID id, long practiceId, ResultOutcome outcome) {
        var candidate = mock(ResultCandidate.class);
        when(candidate.developerId()).thenReturn(5L);
        lenient().when(candidate.practiceId()).thenReturn(practiceId);
        lenient().when(candidate.outcome()).thenReturn(outcome);
        return new AdmittedResultSnapshot(id, candidate, List.of());
    }
}
