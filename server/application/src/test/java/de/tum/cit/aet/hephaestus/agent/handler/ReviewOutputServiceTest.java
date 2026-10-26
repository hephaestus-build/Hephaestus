package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ResultsRefusedException;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.RuntimeNonResultDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SubmittedResultDTO;
import de.tum.cit.aet.hephaestus.evidence.AppliesWhenCheck;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import de.tum.cit.aet.hephaestus.practices.spi.AdmissionReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.WithheldItem;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ReviewOutputServiceTest extends BaseUnitTest {
    private final JsonMapper mapper = new JsonMapper();
    private final ReviewJobAssignments assignments = mock(ReviewJobAssignments.class);
    private final CandidateEvidenceVerifier verifier = mock(CandidateEvidenceVerifier.class);
    private final ResultStore results = mock(ResultStore.class);
    private final ReviewAssignmentStore membership = mock(ReviewAssignmentStore.class);
    private final JobEvidenceFiles files = mock(JobEvidenceFiles.class);
    private final ClasspathSourceCatalogRegistry catalogs = mock(ClasspathSourceCatalogRegistry.class);
    private final ReviewJob job = RuntimeReviewTestData.job();
    private final ReviewOutputService service =
            new ReviewOutputService(assignments, verifier, results, mapper, files, catalogs, membership);

    @BeforeEach
    void setup() {
        var assigned = RuntimeReviewTestData.assignment(5L, null);
        when(assignments.admissible(job)).thenReturn(List.of(assigned));
        when(assignments.review(job)).thenReturn(RuntimeReviewTestData.review(ReviewKind.ITEM, assigned));
        var manifest = new JobFolderIndex(
                new SourceContractVersion("2.0.0"),
                "a".repeat(64),
                "docs.document",
                Instant.EPOCH,
                List.of(new SourceCapture(
                        new SourceKind("docs.document.core"),
                        new SourceCaptureState.NotCollected(SourceAbsenceReason.GOVERNANCE_NOT_EFFECTIVE),
                        EvidenceSnapshotFixtures.citation(new SourceKind("docs.document.core")),
                        List.of())),
                List.of(),
                List.of(),
                List.of());
        var capture = mapper.createObjectNode();
        capture.set("manifest", mapper.valueToTree(manifest));
        job.setCapture(capture);
        job.setWorkerId("worker");
    }

    private void acceptReadReceipts() {
        when(files.registerReadReceipts(eq(job), any(), eq(List.of()), eq(catalogs)))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    private AdmissionRequestDTO submission(String slug, String version) {
        var candidate = mapper.readTree("""
            {"outcome":"MET","summary":"Clear names","explanation":"The names explain the document.",
             "citations":[{"sourceKind":"docs.document.core","sourcePath":"context/body.txt","path":"document/body","startLine":1,"endLine":1,"quote":"Clear names"}],
             "evidence":{},"coverage":{"source":"host","reads":1,"truncatedReads":0,"failedReads":0,"receipts":[],"complete":true}}
            """);
        return new AdmissionRequestDTO(
                3,
                job.getReviewId(),
                job.getId(),
                0,
                List.of(new SubmittedResultDTO(slug, "2", version, candidate, List.of())),
                List.of());
    }

    @Test
    void shouldWithholdAnUnverifiedPracticeThroughTheOneMembershipPort() {
        acceptReadReceipts();
        doThrow(new CandidateEvidenceVerifier.Unverified(
                        CandidateRefusal.QUOTE_UNVERIFIED, "The text was unavailable", null))
                .when(verifier)
                .verifyCandidate(eq(job), any(), eq(ReviewKind.ITEM), any(), any());
        when(results.admit(1, job.getReviewId(), job.getId(), 0, List.of()))
                .thenReturn(new AdmissionReceipt(List.of(), List.of()));
        var prepared = service.prepare(job, submission("clear-names", "docs:one"));
        verifyNoInteractions(membership, results);
        prepared.record(job);
        verify(membership)
                .withhold(
                        1,
                        RuntimeReviewTestData.assignment(5L, null).id(),
                        CandidateRefusal.QUOTE_UNVERIFIED,
                        "The text was unavailable");
        assertThat(Objects.requireNonNull(job.getCapture())
                        .path("withheld")
                        .get(0)
                        .path("reason")
                        .asString())
                .isEqualTo("quote_unverified");
    }

    @Test
    void shouldWithholdAClaimedMetWithoutWritingAResultWhenTheCoverageIsNotMeasured() {
        var submission = submission("clear-names", "docs:one");
        var claimed = ((ObjectNode) submission.results().getFirst().candidate());
        claimed.putObject("coverage")
                .put("source", "claimed")
                .put("selected", 1)
                .put("checked", 1)
                .put("total", 1)
                .put("complete", true);
        when(results.admit(1, job.getReviewId(), job.getId(), 0, List.of()))
                .thenReturn(new AdmissionReceipt(List.of(), List.of()));

        service.prepare(job, submission).record(job);

        verifyNoInteractions(verifier);
        verify(results).admit(1, job.getReviewId(), job.getId(), 0, List.of());
        verify(membership)
                .withhold(
                        eq(1L),
                        eq(RuntimeReviewTestData.assignment(5L, null).id()),
                        eq(CandidateRefusal.COVERAGE_INCONSISTENT),
                        any());
    }

    @Test
    void shouldWithholdHostCoverageWhenItsReceiptsDifferFromTheSubmission() {
        var submitted = submission("clear-names", "docs:one");
        var entry = submitted.results().getFirst();
        var candidate = (ObjectNode) entry.candidate();
        candidate.set("coverage", mapper.readTree("""
            {"source":"host","complete":true,"reads":1,"truncatedReads":0,"failedReads":0,
             "receipts":[{"sourceKind":"docs.document.core","sourcePath":"context/body.txt",
             "path":"document/body","version":"docs:one","status":"COMPLETE"}]}
            """));
        when(results.admit(1, job.getReviewId(), job.getId(), 0, List.of()))
                .thenReturn(new AdmissionReceipt(List.of(), List.of()));
        service.prepare(job, submitted).record(job);
        verifyNoInteractions(verifier);
        verify(membership)
                .withhold(
                        1,
                        RuntimeReviewTestData.assignment(5L, null).id(),
                        CandidateRefusal.COVERAGE_INCONSISTENT,
                        "Host coverage differs from the submitted read receipts");
    }

    @Test
    void shouldRejectAChangedItemVersionBeforeItCanProduceProof() {
        assertThatThrownBy(() -> service.prepare(job, submission("clear-names", "docs:other")))
                .isInstanceOf(ResultsRefusedException.class);
        verifyNoInteractions(results, membership, verifier);
    }

    @Test
    void shouldRejectAnUnassignedPractice() {
        assertThatThrownBy(() -> service.prepare(job, submission("other-practice", "docs:one")))
                .isInstanceOf(ResultsRefusedException.class);
        verifyNoInteractions(results, membership, verifier);
    }

    @Test
    void shouldKeepTheWorkerAttemptFenceAroundTheProductWrite() {
        acceptReadReceipts();
        when(verifier.verifyCandidate(eq(job), any(), eq(ReviewKind.ITEM), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        var prepared = service.prepare(job, submission("clear-names", "docs:one"));
        job.setAttempt(1);
        assertThatThrownBy(() -> prepared.record(job)).isInstanceOf(ResultAdmissionService.StaleAttemptException.class);
        verifyNoInteractions(results, membership);
    }

    @Test
    void shouldKeepAnAdmissiblePracticeWhenAnotherCandidateBreaksItsContract() {
        acceptReadReceipts();
        when(verifier.verifyCandidate(eq(job), any(), eq(ReviewKind.ITEM), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        var first = RuntimeReviewTestData.assignment(5L, null);
        var second = new ReviewAssignmentSnapshot(
                new ReviewAssignmentId(job.getReviewId(), 41, 2),
                first.item(),
                first.version(),
                first.developerId(),
                "other-practice",
                new PinnedPractice(
                        41,
                        42,
                        first.practice().definition(),
                        first.practice().reviewDigest(),
                        first.practice().method()));
        when(assignments.admissible(job)).thenReturn(List.of(first, second));
        when(results.admit(eq(1L), eq(job.getReviewId()), eq(job.getId()), eq(0), any()))
                .thenReturn(new AdmissionReceipt(List.of(), List.of()));
        var valid = submission("clear-names", "docs:one").results().getFirst();
        var malformed = new SubmittedResultDTO("other-practice", "2", "docs:one", mapper.createObjectNode(), List.of());
        var prepared = service.prepare(
                job,
                new AdmissionRequestDTO(3, job.getReviewId(), job.getId(), 0, List.of(valid, malformed), List.of()));
        prepared.record(job);
        ArgumentCaptor<List<ResultCandidate>> admitted = ArgumentCaptor.captor();
        verify(results).admit(eq(1L), eq(job.getReviewId()), eq(job.getId()), eq(0), admitted.capture());
        assertThat(admitted.getValue())
                .singleElement()
                .satisfies(result -> assertThat(result.practiceId()).isEqualTo(31));
        assertThat(Objects.requireNonNull(job.getCapture())
                        .path("withheld")
                        .get(0)
                        .path("practiceSlug")
                        .asString())
                .isEqualTo("other-practice");
        assertThat(Objects.requireNonNull(job.getCapture())
                        .path("withheld")
                        .get(0)
                        .path("reason")
                        .asString())
                .isEqualTo("malformed_candidate");
    }

    @Test
    void shouldAdmitACompletedPracticeWhenAnotherExhaustsItsRuntimeBudget() {
        acceptReadReceipts();
        when(verifier.verifyCandidate(eq(job), any(), eq(ReviewKind.ITEM), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        var first = RuntimeReviewTestData.assignment(5L, null);
        var second = new ReviewAssignmentSnapshot(
                new ReviewAssignmentId(job.getReviewId(), 41, 2),
                first.item(),
                first.version(),
                first.developerId(),
                "other-practice",
                new PinnedPractice(
                        41,
                        42,
                        first.practice().definition(),
                        first.practice().reviewDigest(),
                        first.practice().method()));
        when(assignments.admissible(job)).thenReturn(List.of(first, second));
        when(results.admit(eq(1L), eq(job.getReviewId()), eq(job.getId()), eq(0), any()))
                .thenReturn(new AdmissionReceipt(List.of(), List.of()));
        var request = new AdmissionRequestDTO(
                3,
                job.getReviewId(),
                job.getId(),
                0,
                submission("clear-names", "docs:one").results(),
                List.of(new RuntimeNonResultDTO("other-practice", ReviewStopReason.BUDGET)));
        var prepared = service.prepare(job, request);
        verifyNoInteractions(results, membership);
        prepared.record(job);
        ArgumentCaptor<List<ResultCandidate>> admitted = ArgumentCaptor.captor();
        verify(results).admit(eq(1L), eq(job.getReviewId()), eq(job.getId()), eq(0), admitted.capture());
        assertThat(admitted.getValue())
                .singleElement()
                .satisfies(result -> assertThat(result.practiceId()).isEqualTo(31));
        assertThat(Objects.requireNonNull(job.getCapture()).path("nonResults"))
                .isEqualTo(mapper.valueToTree(request.nonResults()));
        verifyNoInteractions(membership);
    }

    @Test
    void shouldRefuseARunThatHasOnlyRuntimeNonResults() {
        var request = new AdmissionRequestDTO(
                3,
                job.getReviewId(),
                job.getId(),
                0,
                List.of(),
                List.of(new RuntimeNonResultDTO("clear-names", ReviewStopReason.BUDGET)));
        assertThatThrownBy(() -> service.prepare(job, request))
                .isInstanceOf(ResultsRefusedException.class)
                .hasMessage("The submission contains no practice results");
        verifyNoInteractions(results, membership, verifier);
    }

    @Test
    void shouldRejectADuplicateRuntimeNonResultOrAnUnassignedPractice() {
        for (var slug : List.of("clear-names", "unknown")) {
            var request = new AdmissionRequestDTO(
                    3,
                    job.getReviewId(),
                    job.getId(),
                    0,
                    submission("clear-names", "docs:one").results(),
                    List.of(new RuntimeNonResultDTO(slug, ReviewStopReason.BUDGET)));
            assertThatThrownBy(() -> service.prepare(job, request)).isInstanceOf(ResultsRefusedException.class);
        }
        verifyNoInteractions(results, membership);
    }

    @Test
    void shouldAdmitAHostInapplicabilityResultWithoutAModelSubmission() {
        var assigned = RuntimeReviewTestData.assignment(5L, null);
        when(assignments.load(job)).thenReturn(List.of(assigned));
        when(assignments.admissible(job)).thenReturn(List.of());
        var check = new AppliesWhenCheck(
                true,
                "The change touches no Swift file.",
                List.of(new AppliesWhenCheck.Clause(
                        "CHANGED_PATH", new SourceKind("scm.pull-request.diff"), AppliesWhenCheck.Result.NOT_FOUND)));
        var capture = (ObjectNode) Objects.requireNonNull(job.getCapture());
        capture.putObject("readiness")
                .putObject("applicability")
                .set(assigned.practiceSlug(), mapper.valueToTree(check));
        when(results.admit(eq(1L), eq(job.getReviewId()), eq(job.getId()), eq(0), any()))
                .thenReturn(new AdmissionReceipt(List.of(), List.of()));
        service.prepare(job, new AdmissionRequestDTO(3, job.getReviewId(), job.getId(), 0, List.of(), List.of()))
                .record(job);
        ArgumentCaptor<List<ResultCandidate>> candidates = ArgumentCaptor.captor();
        verify(results).admit(eq(1L), eq(job.getReviewId()), eq(job.getId()), eq(0), candidates.capture());
        assertThat(candidates.getValue()).singleElement().satisfies(candidate -> {
            assertThat(candidate.outcome()).isEqualTo(ResultOutcome.DOES_NOT_APPLY);
            assertThat(candidate.explanation()).isEqualTo(check.otherwise());
            assertThat(candidate.problems()).isEmpty();
            assertThat(candidate.evidence().inapplicability()).isNotNull();
        });
        verifyNoInteractions(verifier);
    }

    @Test
    void shouldRetainAnAdmissionRefusalForAHostInapplicabilityResult() {
        var assigned = RuntimeReviewTestData.assignment(5L, null);
        when(assignments.load(job)).thenReturn(List.of(assigned));
        when(assignments.admissible(job)).thenReturn(List.of());
        var check = new AppliesWhenCheck(
                true,
                "The change touches no Swift file.",
                List.of(new AppliesWhenCheck.Clause(
                        "CHANGED_PATH", new SourceKind("scm.pull-request.diff"), AppliesWhenCheck.Result.NOT_FOUND)));
        var capture = (ObjectNode) Objects.requireNonNull(job.getCapture());
        capture.putObject("readiness")
                .putObject("applicability")
                .set(assigned.practiceSlug(), mapper.valueToTree(check));
        when(results.admit(eq(1L), eq(job.getReviewId()), eq(job.getId()), eq(0), any()))
                .thenReturn(new AdmissionReceipt(
                        List.of(),
                        List.of(new WithheldItem(
                                assigned.practice().practiceId(),
                                assigned.item().itemId(),
                                "ROLE_UNAVAILABLE"))));
        service.prepare(job, new AdmissionRequestDTO(3, job.getReviewId(), job.getId(), 0, List.of(), List.of()))
                .record(job);
        assertThat(Objects.requireNonNull(job.getCapture())
                        .path("withheld")
                        .get(0)
                        .path("reason")
                        .asString())
                .isEqualTo("ROLE_UNAVAILABLE");
    }
}
