package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.HistoryCopies;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PublicFeedbackSnapshot;
import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewHistoryContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackPackage;
import de.tum.cit.aet.hephaestus.agent.handler.composition.PinnedInlineAnchorQuery;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasis;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasisRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PracticeStartRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SealResultsRequestDTO;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.spi.AdmissionReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultQuery;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionContext;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepair;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepairStatus;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStore;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStagingStore;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedFeedback;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedPackage;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedReviewQuery;
import de.tum.cit.aet.hephaestus.practices.spi.PrivateFeedbackProgress;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewFreshness;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewWriteGuard;
import de.tum.cit.aet.hephaestus.practices.spi.WithheldItem;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class ResultAdmissionServiceTest extends BaseUnitTest {
    private final AgentRuns runs = mock(AgentRuns.class);
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
    private final PracticeStagingStore stagingStore = mock(PracticeStagingStore.class);
    private final ReviewFreshness freshness = mock(ReviewFreshness.class);
    private final FeedbackRepair repairs = mock(FeedbackRepair.class);
    private final ReviewHistoryContentSource history = mock(ReviewHistoryContentSource.class);
    private final FeedbackCompositionService composition = new FeedbackCompositionService(
            feedback,
            mock(PreparedReviewQuery.class),
            members,
            mock(ResultHistoryQuery.class),
            mock(HistoryCopies.class),
            mock(FeedbackHistoryQuery.class),
            repairs,
            mock(ProductReferenceQuery.class));
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
                history,
                mapper,
                manager,
                composition,
                mock(ReviewWriteGuard.class),
                new PracticeResultStaging(
                        output,
                        stagingStore,
                        members,
                        freshness,
                        assignments,
                        files,
                        bases,
                        mapper,
                        new PracticeCatalogInjector(mapper)),
                runs);
        lenient().when(jobs.databaseClock()).thenAnswer(call -> Instant.now());
        lenient().when(members.endedPractices(anyLong(), any(), any())).thenReturn(Set.of(31L));
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setRunId(LEASE);
        job.setCapture(mapper.createObjectNode());
        when(jobs.findByIdAndWorkspaceId(job.getId(), 1L)).thenReturn(Optional.of(job));
        when(jobs.findByIdAndWorkspaceIdForUpdate(job.getId(), 1)).thenReturn(Optional.of(job));
        when(jobs.databaseClock()).thenReturn(Instant.now());
    }

    private ResultAdmissionService.AdmissionIdentity identity() {
        return new ResultAdmissionService.AdmissionIdentity(job.getId(), 1, 0, LEASE);
    }

    @Test
    void shouldSyncThePracticeSourcesWithNoTransactionBeforeTheLockedStart() {
        when(assignments.start(job, "review-ready-work")).thenReturn(true);

        var started = service.start(identity(), new PracticeStartRequestDTO("review-ready-work"));

        assertThat(started.current()).isTrue();
        var order = inOrder(assignments, manager);
        order.verify(assignments).syncSources(job, "review-ready-work");
        order.verify(manager).getTransaction(any());
        order.verify(assignments).start(job, "review-ready-work");
    }

    @Test
    void shouldStillCheckThePracticeWhenItsSourcesCannotBeSynced() {
        doThrow(new IllegalStateException("The provider cannot be reached"))
                .when(assignments)
                .syncSources(job, "review-ready-work");
        when(assignments.start(job, "review-ready-work")).thenReturn(false);

        var started = service.start(identity(), new PracticeStartRequestDTO("review-ready-work"));

        assertThat(started.current()).isFalse();
        verify(assignments).start(job, "review-ready-work");
    }

    @Test
    void shouldSyncTheBasisSourcesWithNoTransactionBeforeAPackageIsPublished() {
        var basis = UUID.randomUUID();

        assertThatThrownBy(() ->
                        service.publishPackage(identity(), new FeedbackPackage(List.of(basis), List.of(), false, null)))
                .isInstanceOf(AdmissionConflictException.class);

        var order = inOrder(assignments, manager);
        order.verify(assignments).syncBasis(1L, List.of(basis));
        order.verify(manager).getTransaction(any());
    }

    @Test
    void shouldNotSyncWhenAPackageIsOnlyChecked() {
        assertThatThrownBy(() -> service.checkPackage(
                        identity(), new FeedbackPackage(List.of(UUID.randomUUID()), List.of(), false, null)))
                .isInstanceOf(AdmissionConflictException.class);

        verify(assignments, never()).syncBasis(anyLong(), any());
    }

    @Test
    void shouldAcceptALiveDatabaseLeaseWhenTheServerClockIsAhead() {
        var databaseNow = Instant.now().minusSeconds(7200);
        when(jobs.databaseClock()).thenReturn(databaseNow);

        assertThat(service.ownedJob(identity())).isSameAs(job);
    }

    @Test
    void shouldRejectAnAttemptWhoseRunLeaseExpiredOnTheDatabaseClock() {
        // The run's fence refuses a lease that its database clock has passed, whatever the server's clock says.
        doThrow(new StaleRunException(new AgentRunFence(LEASE, 1, 1)))
                .when(runs)
                .requireCurrent(any());

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
        var request = new FeedbackPackage(
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
        var response = service.publishPackage(identity(), request);
        assertThat(response.pieces().stream().map(PreparedFeedback::feedbackId).toList())
                .containsExactly(feedbackId);
        assertThat(response.packageDigest()).isEqualTo("package-digest");
        assertThat(response.preparedAt()).isEqualTo(preparedAt);
        assertThat(job.getPublicReviewDigest()).isEqualTo("package-digest");
        verify(feedback).prepareAll(eq(1L), eq(job.getReviewId()), any());
        verify(members).publicReviewed(1, job.getReviewId(), job.getId(), 0, preparedAt);
    }

    @Test
    void shouldRejectAnOldWorkerAttemptBeforePreparingAnything() {
        var stale = new ResultAdmissionService.AdmissionIdentity(job.getId(), 1, 1, LEASE);
        assertThatThrownBy(() -> service.publishPackage(stale, new FeedbackPackage(List.of(), List.of(), false, null)))
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

    @Test
    void shouldAdmitWithAKeptBasisWithoutBuildingTheMissingFolder() {
        var request = admissionWithRetainedInputs(null);
        var basis = new ReviewCompositionBasis();
        basis.setCopyId(UUID.randomUUID());
        basis.setExpiresAt(Instant.now().plusSeconds(3600));
        var snapshot = EvidenceSnapshotFixtures.snapshot(mapper);
        var source = EvidenceSnapshotFixtures.availableSource(snapshot, "docs.document.core", null);
        EvidenceSnapshotFixtures.file(snapshot, source, "context/body.txt", "a".repeat(64));
        var payload = mapper.createObjectNode();
        payload.putObject("files")
                .put(
                        SandboxLayout.MANIFEST_PATH,
                        Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(snapshot.path("manifest"))));
        basis.setPayload(payload);
        when(bases.findByWorkspaceIdAndJobId(1L, job.getId())).thenReturn(Optional.of(basis));
        when(files.vouchesForOriginalCopy(basis.getCopyId(), job.getId(), 1L, 0))
                .thenReturn(true);

        service.admit(identity(), request);

        assertThat(ResultAdmissionService.isAdmitted(job)).isTrue();
        assertThat(basis.getAdmissionDigest()).isEqualTo(job.getAdmissionDigest());
        verify(files, never()).ensureAttemptFolder(any(ResultAdmissionService.AdmissionIdentity.class));
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
        var response = service.publishPackage(identity(), new FeedbackPackage(List.of(), List.of(), true, null));
        assertThat(response.pieces().stream().map(PreparedFeedback::feedbackId).toList())
                .isEmpty();
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
        service.publishPackage(identity(), new FeedbackPackage(List.of(), List.of(), false, null));
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

    private ReviewAssignmentSnapshot sealable() {
        var assignment = RuntimeReviewTestData.assignment(5L, null);
        when(assignments.review(job)).thenReturn(RuntimeReviewTestData.review(ReviewKind.ITEM, assignment));
        when(assignments.load(job)).thenReturn(List.of(assignment));
        when(proof.forAttempt(1, job.getReviewId(), job.getId(), 0)).thenReturn(List.of());
        when(publicEligibility.publicResultIds(job, List.of())).thenReturn(Set.of());
        return assignment;
    }

    private SealResultsRequestDTO sealRequest() {
        return new SealResultsRequestDTO(SandboxLayout.PRACTICE_CONTRACT_VERSION, job.getReviewId(), job.getId(), 0);
    }

    /** A staged practice whose evidence copy {@code copyId} is kept until {@code expiresAt}. */
    private PracticeStagingStore.StagedPractice stagedCandidate(
            ReviewAssignmentSnapshot assignment, ResultCandidate candidate, UUID copyId, Instant expiresAt) {
        var evidence =
                mapper.createObjectNode().put("copyId", copyId.toString()).put("expiresAt", expiresAt.toString());
        return new PracticeStagingStore.StagedPractice(assignment.id(), 0, "staged", candidate, evidence, null);
    }

    /** The staged practice as the staging store keeps it after it refused the evidence copy. */
    private PracticeStagingStore.StagedPractice refusedCopy(ReviewAssignmentSnapshot assignment) {
        return new PracticeStagingStore.StagedPractice(
                assignment.id(), 0, "staged", null, mapper.createObjectNode(), "COPY_AUTHORITY_UNAVAILABLE");
    }

    @Test
    void shouldRefuseToSealWhenAnAssignmentIsStillOpen() {
        var assignment = sealable();
        when(assignments.open(job)).thenReturn(List.of(assignment));
        // A seal that ignored the open assignment would succeed, so the test fails as a missing conflict.
        lenient().when(output.seal(job, List.of())).thenReturn(new AdmissionReceipt(List.of(), List.of()));

        assertThatThrownBy(() -> service.admit(identity(), sealRequest()))
                .isInstanceOf(AdmissionConflictException.class);

        assertThat(ResultAdmissionService.isAdmitted(job)).isFalse();
        verify(output, never()).seal(any(), any());
    }

    @Test
    void shouldRefuseAReplayedSealWhenTheJobContinuesAnEarlierAttempt() {
        sealable();
        ReflectionTestUtils.setField(job, "attempt", 1);
        var continuing = new ResultAdmissionService.AdmissionIdentity(job.getId(), 1, 1, LEASE);
        var request =
                new SealResultsRequestDTO(SandboxLayout.PRACTICE_CONTRACT_VERSION, job.getReviewId(), job.getId(), 1);
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest(ProvenanceDigest.sha256Hex(mapper.writeValueAsBytes(request)));

        assertThatThrownBy(() -> service.admit(continuing, request)).isInstanceOf(AdmissionConflictException.class);
    }

    @Test
    void shouldRefuseTheEvidenceCopyAndWithholdThePracticeWhenTheCopyExpired() {
        var assignment = sealable();
        var copy = UUID.randomUUID();
        var candidate = mock(ResultCandidate.class);
        var expired = Instant.now().minusSeconds(60);
        var nonResult = new PracticeStagingStore.StagedPractice(
                assignment.id(), 0, "non-result", null, mapper.createObjectNode(), null);
        when(stagingStore.staged(1, job.getReviewId(), job.getId()))
                .thenReturn(List.of(nonResult, stagedCandidate(assignment, candidate, copy, expired)))
                .thenReturn(List.of(refusedCopy(assignment)));
        when(files.vouchesForOriginalCopy(copy, job.getId(), 1L, 0)).thenReturn(true);
        when(output.seal(job, List.of())).thenReturn(new AdmissionReceipt(List.of(), List.of()));

        var response = service.admit(identity(), sealRequest());

        verify(stagingStore).refuseCopy(1, job.getId(), 0, assignment.id());
        assertThat(response.withheld())
                .containsExactly(new AdmissionResponseDTO.Withheld("clear-names", "2", "COPY_AUTHORITY_UNAVAILABLE"));
    }

    @Test
    void shouldRefuseTheEvidenceCopyWhenThisServerCannotVouchForIt() {
        var assignment = sealable();
        var copy = UUID.randomUUID();
        var candidate = mock(ResultCandidate.class);
        var staged = stagedCandidate(assignment, candidate, copy, Instant.now().plusSeconds(3600));
        when(stagingStore.staged(1, job.getReviewId(), job.getId()))
                .thenReturn(List.of(staged))
                .thenReturn(List.of(refusedCopy(assignment)));
        when(files.vouchesForOriginalCopy(copy, job.getId(), 1L, 0)).thenReturn(false);
        when(output.seal(job, List.of())).thenReturn(new AdmissionReceipt(List.of(), List.of()));

        var response = service.admit(identity(), sealRequest());

        verify(stagingStore).refuseCopy(1, job.getId(), 0, assignment.id());
        assertThat(response.withheld())
                .containsExactly(new AdmissionResponseDTO.Withheld("clear-names", "2", "COPY_AUTHORITY_UNAVAILABLE"));
    }

    @Test
    void shouldKeepTheEvidenceCopyWhenItIsVouchedForAndUnexpired() {
        var assignment = sealable();
        var copy = UUID.randomUUID();
        var candidate = mock(ResultCandidate.class);
        var staged = stagedCandidate(assignment, candidate, copy, Instant.now().plusSeconds(3600));
        when(stagingStore.staged(1, job.getReviewId(), job.getId())).thenReturn(List.of(staged));
        when(files.vouchesForOriginalCopy(copy, job.getId(), 1L, 0)).thenReturn(true);
        when(output.seal(job, List.of(candidate))).thenReturn(new AdmissionReceipt(List.of(), List.of()));

        var response = service.admit(identity(), sealRequest());

        verify(stagingStore, never()).refuseCopy(anyLong(), any(), anyInt(), any());
        assertThat(response.withheld()).isEmpty();
        assertThat(ResultAdmissionService.isAdmitted(job)).isTrue();
    }

    @Test
    void shouldCarryTheReasonInTheResponseWhenTheSealWithholdsAnItem() {
        sealable();
        when(output.seal(job, List.of()))
                .thenReturn(new AdmissionReceipt(List.of(), List.of(new WithheldItem(31, 2, "quote_unverified"))));

        var response = service.admit(identity(), sealRequest());

        assertThat(response.withheld())
                .containsExactly(new AdmissionResponseDTO.Withheld("clear-names", "2", "quote_unverified"));
    }

    @Test
    void shouldCarryEachReasonInTheResponseWhenTheAdmittedCaptureWithholdsItems() {
        sealable();
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("admitted");
        var capture = mapper.createObjectNode();
        capture.putArray("withheld")
                .addObject()
                .put("practiceSlug", "clear-names")
                .put("itemId", "2")
                .put("reason", "source_unavailable");
        job.setCapture(capture);

        var response = service.admittedResponse(job);

        assertThat(response.withheld())
                .containsExactly(new AdmissionResponseDTO.Withheld("clear-names", "2", "source_unavailable"));
        assertThat(response.admissionDigest()).isEqualTo("admitted");
    }

    @Test
    void shouldRefuseAnAdmissionResponseWhenTheJobIsNotAdmitted() {
        assertThatThrownBy(() -> service.admittedResponse(job)).isInstanceOf(AdmissionConflictException.class);
    }

    @Test
    void shouldNotSealAgainWhenTheRetryFindsTheJobAdmitted() {
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("admitted");

        assertThat(service.sealIfComplete(identity())).isFalse();

        verify(output, never()).seal(any(), any());
    }

    @Test
    void shouldNotSealTheRetryWhileACurrentPracticeIsStillOpen() {
        var assignment = sealable();
        when(assignments.open(job)).thenReturn(List.of(assignment));

        assertThat(service.sealIfComplete(identity())).isFalse();

        assertThat(ResultAdmissionService.isAdmitted(job)).isFalse();
    }

    @Test
    void shouldSealTheRetryWhenEveryCurrentPracticeEnded() {
        sealable();
        when(output.seal(job, List.of())).thenReturn(new AdmissionReceipt(List.of(), List.of()));

        assertThat(service.sealIfComplete(identity())).isTrue();

        assertThat(ResultAdmissionService.isAdmitted(job)).isTrue();
    }

    @Test
    void shouldStopTheOpenPracticesAsReplacedAndSealWhenTheRetryFindsTheSourcesStale() {
        assertRetryStopsOpenPractices(Currentness.STALE, ReviewStopReason.REPLACED);
    }

    @Test
    void shouldStopTheOpenPracticesAsReadinessRefusedAndSealWhenTheRetryCannotVerifyTheSources() {
        assertRetryStopsOpenPractices(Currentness.UNVERIFIABLE, ReviewStopReason.READINESS_REFUSED);
    }

    private void assertRetryStopsOpenPractices(Currentness currentness, ReviewStopReason reason) {
        var assignment = sealable();
        when(members.hasStaged(1, job.getReviewId(), job.getId())).thenReturn(true);
        when(freshness.capturedCurrentness(1, job.getReviewId())).thenReturn(currentness);
        // The stop ends the open practice, so the seal that follows finds none open.
        when(assignments.open(job)).thenReturn(List.of(assignment)).thenReturn(List.of());
        when(output.seal(job, List.of())).thenReturn(new AdmissionReceipt(List.of(), List.of()));

        assertThat(service.sealIfComplete(identity())).isTrue();

        verify(members).fail(eq(1L), eq(assignment.id()), eq(job.getId()), eq(reason), any());
        assertThat(ResultAdmissionService.isAdmitted(job)).isTrue();
        assertThat(Objects.requireNonNull(job.getCapture()).path("nonResults")).anySatisfy(nonResult -> {
            assertThat(nonResult.path("practiceSlug").asString()).isEqualTo("clear-names");
            assertThat(nonResult.path("reason").asString()).isEqualTo(reason.name());
        });
    }

    /** An admitted item review whose public history lists {@code listed}, with an admitted result to compose from. */
    private UUID editableReview(PublicFeedbackSnapshot.Entry... listed) {
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("admitted");
        var assignment = RuntimeReviewTestData.assignment(5L, null);
        when(assignments.review(job)).thenReturn(RuntimeReviewTestData.review(ReviewKind.ITEM, assignment));
        when(assignments.load(job)).thenReturn(List.of(assignment));
        when(repairs.status(1, job.getReviewId()))
                .thenReturn(
                        new FeedbackRepairStatus(UUID.randomUUID(), FeedbackRepairStatus.State.READBACK, 0, List.of()));
        var item = new ItemRefDTO("2", "docs.document", null, null, "Document", null, null, null, null, null);
        when(history.publicSameItemFeedback(job, List.of(assignment)))
                .thenReturn(new ReviewHistoryContentSource.PublicHistoryRead(
                        new PublicFeedbackSnapshot(
                                Instant.now(), null, new PublicFeedbackSnapshot.History(item, List.of(listed))),
                        List.of()));
        UUID resultId = UUID.randomUUID();
        var candidate = mock(ResultCandidate.class);
        // A refused edit stops before it reads the admitted results.
        lenient().when(candidate.developerId()).thenReturn(5L);
        lenient()
                .when(proof.forAttempt(1, job.getReviewId(), job.getId(), 0))
                .thenReturn(List.of(new AdmittedResultSnapshot(resultId, candidate, List.of())));
        return resultId;
    }

    private static PublicFeedbackSnapshot.Entry listed(UUID id, boolean withdrawn, boolean bodyUnavailable) {
        return new PublicFeedbackSnapshot.Entry(
                id,
                FeedbackDestination.ON_WORK,
                FeedbackState.DELIVERED,
                Instant.parse("2026-10-14T06:00:00Z"),
                "docs:one",
                bodyUnavailable ? null : "Use clear names.",
                bodyUnavailable,
                Currentness.CURRENT,
                withdrawn,
                List.of(),
                List.of());
    }

    private static FeedbackPackage edit(UUID resultId, UUID target) {
        return new FeedbackPackage(
                List.of(resultId),
                List.of(new ComposedFeedback(
                        FeedbackDestination.ON_WORK,
                        List.of(resultId),
                        "Use clearer names.",
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
                null,
                target);
    }

    @Test
    void shouldRefuseAnEditWhenTheTargetFeedbackWasWithdrawn() {
        var target = UUID.randomUUID();
        var resultId = editableReview(listed(target, true, false));

        assertThatThrownBy(() -> service.checkPackage(identity(), edit(resultId, target)))
                .isInstanceOf(AdmissionConflictException.class);
    }

    @Test
    void shouldRefuseAnEditWhenTheTargetFeedbackBodyIsUnavailable() {
        var target = UUID.randomUUID();
        var resultId = editableReview(listed(target, false, true));

        assertThatThrownBy(() -> service.checkPackage(identity(), edit(resultId, target)))
                .isInstanceOf(AdmissionConflictException.class);
    }

    @Test
    void shouldRefuseAnEditWhenThePublicHistoryDoesNotListTheTarget() {
        var resultId = editableReview(listed(UUID.randomUUID(), false, false));

        assertThatThrownBy(() -> service.checkPackage(identity(), edit(resultId, UUID.randomUUID())))
                .isInstanceOf(AdmissionConflictException.class);
    }

    @Test
    void shouldCheckAnEditWhenTheTargetFeedbackIsListedAndShown() {
        var target = UUID.randomUUID();
        var resultId = editableReview(listed(target, false, false));

        service.checkPackage(identity(), edit(resultId, target));

        verify(repairs).check(any(), eq(target), any());
    }

    @Test
    void shouldCheckAnEditWhenTheTargetIsThisReviewsOwnCopyEvenIfTheHistoryShowsItWithdrawn() {
        var target = UUID.randomUUID();
        var resultId = editableReview(listed(target, true, false));
        when(repairs.status(1, job.getReviewId()))
                .thenReturn(new FeedbackRepairStatus(target, FeedbackRepairStatus.State.READBACK, 0, List.of()));

        service.checkPackage(identity(), edit(resultId, target));

        verify(repairs).check(any(), eq(target), any());
    }

    private static AdmittedResultSnapshot admitted(UUID id, long practiceId, ResultOutcome outcome) {
        var candidate = mock(ResultCandidate.class);
        when(candidate.developerId()).thenReturn(5L);
        lenient().when(candidate.practiceId()).thenReturn(practiceId);
        lenient().when(candidate.outcome()).thenReturn(outcome);
        return new AdmittedResultSnapshot(id, candidate, List.of());
    }
}
