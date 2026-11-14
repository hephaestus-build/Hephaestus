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
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService.AdmissionIdentity;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedResults;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasisRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.RuntimeNonResultDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.StageResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SubmittedResultDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStagingStore;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewFreshness;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class PracticeResultStagingTest extends BaseUnitTest {
    private static final String SLUG = "clear-names";

    private final JsonMapper mapper = new JsonMapper();
    private final ReviewOutputService output = mock(ReviewOutputService.class);
    private final PracticeStagingStore store = mock(PracticeStagingStore.class);
    private final ReviewJobAssignments assignments = mock(ReviewJobAssignments.class);
    private final JobEvidenceFiles files = mock(JobEvidenceFiles.class);
    private final ReviewJob job = RuntimeReviewTestData.job();
    private final AdmissionIdentity identity = new AdmissionIdentity(job.getId(), 1, 0, job.getRunId());
    private final PracticeResultStaging staging = new PracticeResultStaging(
            output,
            store,
            mock(ReviewAssignmentStore.class),
            mock(ReviewFreshness.class),
            assignments,
            files,
            mock(ReviewCompositionBasisRepository.class),
            mapper,
            new PracticeCatalogInjector(mapper));

    @BeforeEach
    void setup() {
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(5L, null)));
    }

    private AdmissionRequestDTO request(List<SubmittedResultDTO> results, List<RuntimeNonResultDTO> nonResults) {
        return new AdmissionRequestDTO(
                SandboxLayout.PRACTICE_CONTRACT_VERSION, job.getReviewId(), job.getId(), 0, results, nonResults);
    }

    private AdmissionRequestDTO nonResult(String slug) {
        return request(List.of(), List.of(new RuntimeNonResultDTO(slug, ReviewStopReason.BUDGET)));
    }

    private SubmittedResultDTO result() {
        return new SubmittedResultDTO(SLUG, "2", "docs:one", mapper.createObjectNode(), List.of());
    }

    private String digest(AdmissionRequestDTO request) {
        return ProvenanceDigest.sha256Hex(mapper.writeValueAsBytes(request));
    }

    private void priorReceipt(String digest) {
        var assignment = RuntimeReviewTestData.assignment(5L, null);
        when(store.receipt(1, job.getReviewId(), job.getId(), assignment.id()))
                .thenReturn(Optional.of(new PracticeStagingStore.StagedPractice(
                        assignment.id(), 0, digest, null, mapper.createObjectNode(), null)));
    }

    private StageResponseDTO stage(AdmissionRequestDTO request) {
        return staging.stage(job, identity, request, () -> {});
    }

    /** Runs the staging callback that the output service hands back once it has verified the submission. */
    @SuppressWarnings("unchecked")
    private void verifiedWithoutCandidates(AdmissionRequestDTO request) {
        when(output.prepareStage(eq(job), eq(request), any())).thenAnswer(call -> {
            var record = (BiConsumer<ReviewJob, List<ResultCandidate>>) call.getArgument(2);
            return (PreparedResults) owned -> record.accept(owned, List.of());
        });
    }

    @Test
    void shouldReturnTheStoredReceiptWithoutStagingAgainWhenTheSameBodyIsReplayed() {
        var request = nonResult(SLUG);
        priorReceipt(digest(request));

        var receipt = stage(request);

        assertThat(receipt).isEqualTo(new StageResponseDTO(SLUG, digest(request)));
        verify(output, never()).prepareStage(any(), any(), any());
        verify(store, never()).stage(anyLong(), any(), any());
    }

    @Test
    void shouldReturnTheStoredReceiptWhenTheSameBodyIsReplayedAfterAdmission() {
        var request = nonResult(SLUG);
        priorReceipt(digest(request));
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("admitted");

        assertThat(stage(request)).isEqualTo(new StageResponseDTO(SLUG, digest(request)));
    }

    @Test
    void shouldRefuseAReplayWhenItsBodyDiffersFromTheStagedOne() {
        priorReceipt("0".repeat(64));

        assertThatThrownBy(() -> stage(nonResult(SLUG))).isInstanceOf(AdmissionConflictException.class);

        verify(store, never()).stage(anyLong(), any(), any());
    }

    @Test
    void shouldRefuseASubmissionWhenItCarriesNeitherAResultNorANonResult() {
        assertThatThrownBy(() -> stage(request(List.of(), List.of()))).isInstanceOf(AdmissionConflictException.class);
    }

    @Test
    void shouldRefuseASubmissionWhenItCarriesBothAResultAndANonResult() {
        var request = request(List.of(result()), List.of(new RuntimeNonResultDTO(SLUG, ReviewStopReason.BUDGET)));

        assertThatThrownBy(() -> stage(request)).isInstanceOf(AdmissionConflictException.class);

        verify(store, never()).receipt(anyLong(), any(), any(), any());
    }

    @Test
    void shouldRefuseASubmissionWhenItCarriesTwoNonResults() {
        var request = request(
                List.of(),
                List.of(
                        new RuntimeNonResultDTO(SLUG, ReviewStopReason.BUDGET),
                        new RuntimeNonResultDTO(SLUG, ReviewStopReason.DEADLINE)));

        assertThatThrownBy(() -> stage(request)).isInstanceOf(AdmissionConflictException.class);
    }

    @Test
    void shouldRefuseAStageWhenThePracticeIsNotAssignedToTheJob() {
        assertThatThrownBy(() -> stage(nonResult("another-practice"))).isInstanceOf(AdmissionConflictException.class);
    }

    @Test
    void shouldRefuseANewStageWhenTheJobIsAlreadyAdmitted() {
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("admitted");

        assertThatThrownBy(() -> stage(nonResult(SLUG))).isInstanceOf(AdmissionConflictException.class);

        verify(output, never()).prepareStage(any(), any(), any());
        verify(store, never()).stage(anyLong(), any(), any());
    }

    @Test
    void shouldRefuseToStageWhenTheOriginalCompositionInputsAreMissing() {
        var request = nonResult(SLUG);
        verifiedWithoutCandidates(request);
        var copy = UUID.randomUUID();
        when(files.originalCompositionInputs(identity)).thenReturn(Optional.empty());
        // The receipt holds, so only the missing inputs can refuse the stage, whichever check runs first.
        lenient().when(files.completeReceipt(identity)).thenReturn(Optional.of(copy));
        lenient().when(files.holdVouchingReceipt(identity, copy)).thenReturn(true);

        assertThatThrownBy(() -> stage(request)).isInstanceOf(AdmissionConflictException.class);

        verify(store, never()).stage(anyLong(), any(), any());
    }

    @Test
    void shouldRefuseToStageWhenTheAttemptHasNoCompleteReceipt() {
        var request = nonResult(SLUG);
        verifiedWithoutCandidates(request);
        when(files.originalCompositionInputs(identity)).thenReturn(Optional.of(Map.of()));
        when(files.completeReceipt(identity)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stage(request)).isInstanceOf(AdmissionConflictException.class);

        verify(store, never()).stage(anyLong(), any(), any());
    }

    @Test
    void shouldStageThePracticeForThisAttemptWhenItsReceiptAndInputsHold() {
        var request = nonResult(SLUG);
        verifiedWithoutCandidates(request);
        var assignment = RuntimeReviewTestData.assignment(5L, null);
        var copy = UUID.randomUUID();
        var capture = EvidenceSnapshotFixtures.snapshot(mapper);
        var source = EvidenceSnapshotFixtures.availableSource(capture, "docs.document.core", null);
        EvidenceSnapshotFixtures.file(capture, source, "context/body.txt", "a".repeat(64));
        job.setCapture(capture);
        when(assignments.review(job)).thenReturn(RuntimeReviewTestData.review(ReviewKind.ITEM, assignment));
        when(files.originalCompositionInputs(identity)).thenReturn(Optional.of(Map.of()));
        when(files.completeReceipt(identity)).thenReturn(Optional.of(copy));
        when(files.holdVouchingReceipt(identity, copy)).thenReturn(true);

        var receipt = stage(request);

        var staged = ArgumentCaptor.forClass(PracticeStagingStore.StagedPractice.class);
        verify(store).stage(eq(1L), eq(job.getId()), staged.capture());
        assertThat(staged.getValue().assignment()).isEqualTo(assignment.id());
        assertThat(staged.getValue().attempt()).isZero();
        assertThat(staged.getValue().digest()).isEqualTo(digest(request));
        assertThat(receipt).isEqualTo(new StageResponseDTO(SLUG, digest(request)));
    }

    @Test
    void shouldTreatTheAttemptAsStaleWhenItCannotHoldItsVouchingReceipt() {
        var request = nonResult(SLUG);
        verifiedWithoutCandidates(request);
        var copy = UUID.randomUUID();
        when(files.originalCompositionInputs(identity)).thenReturn(Optional.of(Map.of()));
        when(files.completeReceipt(identity)).thenReturn(Optional.of(copy));
        when(files.holdVouchingReceipt(identity, copy)).thenReturn(false);

        assertThatThrownBy(() -> stage(request)).isInstanceOf(ResultAdmissionService.StaleAttemptException.class);

        verify(store, never()).stage(anyLong(), any(), any());
    }
}
