package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.AppliesWhen;
import de.tum.cit.aet.hephaestus.practices.AppliesWhenClause;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.ScanDefinition;
import de.tum.cit.aet.hephaestus.practices.spi.AdmissionReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceiptStatus;
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
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
                new SourceContractVersion("3.0.0"),
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

    private static final String PR50_HEAD = "4154fb8c8ed570d8fb117a89a894ddedca72d818";
    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    private static final SourceKind TREE = new SourceKind("scm.repository.tree");

    /** #50's capture: its diff, and its checkout at the head commit, captured as {@code completeness} says. */
    private void pr50Capture(SourceCompleteness completeness) {
        var whole = new SourceCaptureFacts(Instant.EPOCH, null, null, null);
        var tree = new SourceCaptureFacts(Instant.EPOCH, null, null, PR50_HEAD + ":" + "b".repeat(40));
        var manifest = new JobFolderIndex(
                new SourceContractVersion("3.0.0"),
                "a".repeat(64),
                "scm.pull_request",
                Instant.EPOCH,
                List.of(
                        new SourceCapture(
                                DIFF,
                                new SourceCaptureState.Available(
                                        SourceContentState.EMPTY, SourceCompleteness.COMPLETE, whole),
                                EvidenceSnapshotFixtures.citation(DIFF),
                                List.of()),
                        new SourceCapture(
                                TREE,
                                new SourceCaptureState.Available(
                                        SourceContentState.EMPTY,
                                        completeness,
                                        tree,
                                        completeness == SourceCompleteness.COMPLETE
                                                ? List.of()
                                                : List.of("submodule_excluded")),
                                EvidenceSnapshotFixtures.citation(TREE),
                                List.of())),
                List.of(),
                List.of(),
                List.of());
        var capture = mapper.createObjectNode();
        capture.set("manifest", mapper.valueToTree(manifest));
        job.setCapture(capture);
        var real = new ClasspathSourceCatalogRegistry(mapper, Clock.systemUTC());
        when(catalogs.requireSource(any())).thenAnswer(invocation -> real.requireSource(invocation.getArgument(0)));
        when(files.registerReadReceipts(eq(job), any(), any(), eq(catalogs)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(verifier.verifyCandidate(eq(job), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));
    }

    /**
     * The error-handling result that #50's review recorded (review 2435ae89): a Not met that says the checkout has no
     * exception handler, with {@code receipts} as its session proved them. {@code edit} changes the stored text.
     */
    private AdmissionRequestDTO pr50ErrorHandling(List<ReadReceipt> receipts, UnaryOperator<ObjectNode> edit) {
        var candidate = (ObjectNode) mapper.readTree("""
            {"outcome":"NOT_MET",
             "summary":"New page parameter is unvalidated; invalid input yields an unhandled exception and a raw 500",
             "explanation":"A negative page makes from negative, so List.subList throws. No @ControllerAdvice exists in the checkout.",
             "citations":[],
             "problems":[{"severity":"MINOR",
               "summary":"page parameter is unvalidated; negative or overflowing values throw an unhandled IndexOutOfBoundsException",
               "explanation":"No code catches this and the reviewed checkout contains no @ControllerAdvice or @ExceptionHandler, so invalid input surfaces as a raw 500.",
               "citations":[{"sourceKind":"scm.pull-request.diff",
                 "sourcePath":"context/citable/new/src/main/java/com/example/userapi/controller/UserController.java",
                 "path":"src/main/java/com/example/userapi/controller/UserController.java","side":"NEW",
                 "startLine":30,"endLine":30,"quote":"        return ResponseEntity.ok(all.subList(from, to));"}],
               "basis":{"type":"introduced","head":0,"explanation":"The diff adds the unvalidated page parameter."},
               "nextStep":"Validate page before slicing."}],
             "evidence":{"search":{"consulted":["scm.pull-request.diff","scm.repository.tree"],
               "lookedFor":"an error handler for the new page parameter",
               "boundary":"the changed file plus a repo-wide grep of all 8 Java sources"}}}
            """);
        candidate = edit.apply(candidate);
        candidate.set("coverage", mapper.valueToTree(new HostCoverage(true, null, 3, 0, 0, receipts)));
        return new AdmissionRequestDTO(
                3,
                job.getReviewId(),
                job.getId(),
                0,
                List.of(new SubmittedResultDTO("clear-names", "2", "docs:one", candidate, receipts)),
                List.of());
    }

    private static ReadReceipt searched(String version) {
        return new ReadReceipt(
                TREE,
                "repos/reviewed/.git/HEAD",
                ".",
                version,
                ReadReceiptStatus.SEARCHED,
                null,
                null,
                null,
                "ControllerAdvice|ExceptionHandler");
    }

    /** The diff alone named as searched: the claim now rests on its words. */
    private ObjectNode diffOnly(ObjectNode candidate) {
        ((ObjectNode) candidate.path("evidence").path("search"))
                .set("consulted", mapper.valueToTree(List.of(DIFF.value())));
        return candidate;
    }

    /** The stored #50 texts, each replaced by {@code text}. */
    private UnaryOperator<ObjectNode> worded(String text) {
        return candidate -> {
            candidate.put("summary", "The page parameter is not validated");
            candidate.put("explanation", text);
            var problem = (ObjectNode) candidate.path("problems").get(0);
            problem.put("summary", "Negative page reaches subList");
            problem.put("explanation", "A negative page makes from negative, so List.subList throws.");
            return diffOnly(candidate);
        };
    }

    private void assertWithheld(CandidateRefusal refusal, String message) {
        verify(membership)
                .withhold(1, RuntimeReviewTestData.assignment(5L, null).id(), refusal, message);
    }

    private static final String NAMED = "The result names scm.repository.tree as searched";
    private static final String WORDED = "The result says the repository lacks something beyond its citations";
    private static final String UNSEARCHED = ", but its receipts hold no search of the whole checkout to the end";

    @Test
    void shouldWithholdAResultThatConsultedASourceItsPracticeDoesNotDeclare() {
        pr50Capture(SourceCompleteness.COMPLETE);
        when(results.admit(1, job.getReviewId(), job.getId(), 0, List.of()))
                .thenReturn(new AdmissionReceipt(List.of(), List.of()));
        // The practice declares the document only; the review discussion was never its source.
        UnaryOperator<ObjectNode> discussion = candidate -> {
            ((ObjectNode) candidate.path("evidence").path("search"))
                    .set(
                            "consulted",
                            mapper.valueToTree(List.of(DIFF.value(), TREE.value(), "scm.pull-request.comments")));
            return candidate;
        };

        service.prepare(job, pr50ErrorHandling(List.of(searched(PR50_HEAD)), discussion))
                .record(job);

        assertWithheld(
                CandidateRefusal.UNDECLARED_SOURCE_CITED,
                "The result cites scm.pull-request.comments, which the practice does not declare");
    }

    @ParameterizedTest
    @CsvSource({
        "ITEM,search",
        "ITEM,top",
        "ITEM,problem",
        "ITEM,inapplicability",
        "SCAN,search",
        "SCAN,top",
        "SCAN,problem",
        "SCAN,inapplicability"
    })
    void shouldWithholdAResultThatConsultedOnlyAnAppliesWhenSource(ReviewKind kind, String shape) {
        pr50Capture(SourceCompleteness.COMPLETE);
        var original = RuntimeReviewTestData.assignment(5L, null);
        var pinned = original.practice();
        var d = pinned.definition();
        var definition = new PracticeDefinition(
                d.name(),
                d.reviewOn(),
                new AppliesWhen(
                        List.of(new AppliesWhenClause(null, null, "scm.inline-review-comments")),
                        "No inline review comments were captured."),
                d.about(),
                new PracticeReads(
                        "3.0.0",
                        List.of(new PracticeReadSource(
                                kind == ReviewKind.SCAN ? new SourceKind("scm.pull-request.comments") : DIFF,
                                ReadNeed.REQUIRED))),
                d.reviewedBy(),
                d.criteria(),
                d.script(),
                new ScanDefinition(
                        new ItemKind("scm.file"),
                        List.of("**/*.java"),
                        List.of(),
                        List.of(),
                        new PracticeReads("3.0.0", List.of(new PracticeReadSource(DIFF, ReadNeed.REQUIRED)))),
                d.lineNotes(),
                d.guidance());
        var assignment = new ReviewAssignmentSnapshot(
                original.id(),
                original.item(),
                original.version(),
                original.developerId(),
                original.practiceSlug(),
                new PinnedPractice(
                        pinned.practiceId(),
                        pinned.practiceVersionId(),
                        definition,
                        pinned.reviewDigest(),
                        pinned.method()));
        when(assignments.admissible(job)).thenReturn(List.of(assignment));
        when(assignments.review(job)).thenReturn(RuntimeReviewTestData.review(kind, assignment));
        // FOUND permits the session to run; it does not add this source to the session's Reads.
        ((ObjectNode) Objects.requireNonNull(job.getCapture()))
                .putObject("readiness")
                .putObject("applicability")
                .putObject("clear-names")
                .put("absent", false)
                .putArray("clauses")
                .addObject()
                .put("readFrom", "scm.pull-request.comments")
                .put("result", "FOUND");
        when(results.admit(eq(1L), eq(job.getReviewId()), eq(job.getId()), eq(0), any()))
                .thenReturn(new AdmissionReceipt(List.of(), List.of()));
        UnaryOperator<ObjectNode> discussion = candidate -> {
            var citation = ((ObjectNode)
                            candidate.path("problems").get(0).path("citations").get(0))
                    .deepCopy();
            citation.put("sourceKind", "scm.pull-request.comments");
            citation.put("sourcePath", "context/comments.json");
            switch (shape) {
                case "search" ->
                    ((ObjectNode) candidate.path("evidence").path("search"))
                            .set(
                                    "consulted",
                                    mapper.valueToTree(
                                            List.of(DIFF.value(), TREE.value(), "scm.pull-request.comments")));
                case "top" -> candidate.putArray("citations").add(citation);
                case "problem" ->
                    ((ObjectNode) candidate.path("problems").get(0))
                            .putArray("citations")
                            .add(citation);
                case "inapplicability" -> {
                    candidate.put("outcome", "DOES_NOT_APPLY");
                    candidate.remove("problems");
                    candidate.putArray("citations").add(citation.deepCopy().put("sourceKind", DIFF.value()));
                    candidate
                            .putObject("evidence")
                            .putObject("inapplicability")
                            .put("prerequisite", "Inline comments")
                            .put("ruledOutBy", "The captured discussion rules it out")
                            .putArray("consulted")
                            .add("scm.pull-request.comments");
                }
                default -> throw new IllegalArgumentException(shape);
            }
            return candidate;
        };

        service.prepare(job, pr50ErrorHandling(List.of(searched(PR50_HEAD)), discussion))
                .record(job);

        assertWithheld(
                CandidateRefusal.UNDECLARED_SOURCE_CITED,
                "The result cites scm.pull-request.comments, which the practice does not declare");
    }

    @Test
    void shouldWithholdAClaimAboutTheCheckoutWhenItsSessionNeitherReadNorSearchedIt() {
        pr50Capture(SourceCompleteness.COMPLETE);
        when(results.admit(1, job.getReviewId(), job.getId(), 0, List.of()))
                .thenReturn(new AdmissionReceipt(List.of(), List.of()));

        service.prepare(job, pr50ErrorHandling(List.of(), UnaryOperator.identity()))
                .record(job);

        assertWithheld(CandidateRefusal.CONSULTED_SOURCE_UNREAD, NAMED + UNSEARCHED);
    }

    @Test
    void shouldWithholdAClaimAboutTheCheckoutWhenItsSessionReadOnlyTheChangedFile() {
        pr50Capture(SourceCompleteness.COMPLETE);
        // The stored #50 result read UserController to its end; that is a complete source of one file, not of the tree.
        var oneFile = List.of(new ReadReceipt(
                TREE,
                "repos/reviewed/.git/HEAD",
                "src/main/java/com/example/userapi/controller/UserController.java",
                PR50_HEAD,
                ReadReceiptStatus.COMPLETE,
                "f77392a044225e8fdda78f2e6da1b952472a1e70f854696c413a88c8ec8aeb67",
                1,
                41,
                null));
        when(results.admit(1, job.getReviewId(), job.getId(), 0, List.of()))
                .thenReturn(new AdmissionReceipt(List.of(), List.of()));

        service.prepare(job, pr50ErrorHandling(oneFile, UnaryOperator.identity()))
                .record(job);

        assertWithheld(CandidateRefusal.CONSULTED_SOURCE_UNREAD, NAMED + UNSEARCHED);
    }

    @Test
    void shouldAdmitAClaimAboutTheCheckoutWhenItsSessionSearchedTheWholeCompleteCheckoutToTheEnd() {
        pr50Capture(SourceCompleteness.COMPLETE);

        service.prepare(job, pr50ErrorHandling(List.of(searched(PR50_HEAD)), UnaryOperator.identity()));

        verify(membership, never()).withhold(anyLong(), any(), any(), any());
    }

    @Test
    void shouldWithholdAClaimAboutTheCheckoutWhenTheCaptureLeftFilesOutOfIt() {
        pr50Capture(SourceCompleteness.PARTIAL);
        when(results.admit(1, job.getReviewId(), job.getId(), 0, List.of()))
                .thenReturn(new AdmissionReceipt(List.of(), List.of()));

        service.prepare(job, pr50ErrorHandling(List.of(searched(PR50_HEAD)), UnaryOperator.identity()))
                .record(job);

        assertWithheld(
                CandidateRefusal.SOURCE_UNAVAILABLE,
                NAMED + ", but the capture does not hold the whole checkout, so no search can show that");
    }

    @Test
    void shouldWithholdAClaimAboutTheCheckoutWhenTheSearchRanOverAnotherCommit() {
        pr50Capture(SourceCompleteness.COMPLETE);
        when(results.admit(1, job.getReviewId(), job.getId(), 0, List.of()))
                .thenReturn(new AdmissionReceipt(List.of(), List.of()));

        service.prepare(job, pr50ErrorHandling(List.of(searched("c".repeat(40))), UnaryOperator.identity()))
                .record(job);

        assertWithheld(CandidateRefusal.CONSULTED_SOURCE_UNREAD, NAMED + UNSEARCHED);
    }

    @Test
    void shouldWithholdWordsThatSayTheRepositoryLacksSomethingWhenTheResultLeavesTheTreeUnnamed() {
        pr50Capture(SourceCompleteness.COMPLETE);
        when(results.admit(1, job.getReviewId(), job.getId(), 0, List.of()))
                .thenReturn(new AdmissionReceipt(List.of(), List.of()));

        service.prepare(
                        job,
                        pr50ErrorHandling(List.of(), worded("The application defines no global exception handler.")))
                .record(job);

        assertWithheld(CandidateRefusal.CONSULTED_SOURCE_UNREAD, WORDED + UNSEARCHED);
    }

    @Test
    void shouldWithholdWordsThatSayTheRepositoryLacksSomethingWhenOnlyAProblemBasisSaysIt() {
        pr50Capture(SourceCompleteness.COMPLETE);
        when(results.admit(1, job.getReviewId(), job.getId(), 0, List.of()))
                .thenReturn(new AdmissionReceipt(List.of(), List.of()));

        service.prepare(job, pr50ErrorHandling(List.of(), candidate -> {
                    var bounded = worded("The pinned file has no @Min, clamp or other check on the path.")
                            .apply(candidate);
                    ((ObjectNode) bounded.path("problems").get(0).path("basis"))
                            .put(
                                    "explanation",
                                    "The diff adds the parameter, and no error mapping exists in the checkout.");
                    return bounded;
                }))
                .record(job);

        assertWithheld(CandidateRefusal.CONSULTED_SOURCE_UNREAD, WORDED + UNSEARCHED);
    }

    @Test
    void shouldWithholdWordsThatSayTheRepositoryLacksSomethingWhenOnlyTheSearchBoundarySaysIt() {
        pr50Capture(SourceCompleteness.COMPLETE);
        when(results.admit(1, job.getReviewId(), job.getId(), 0, List.of()))
                .thenReturn(new AdmissionReceipt(List.of(), List.of()));

        service.prepare(job, pr50ErrorHandling(List.of(), candidate -> {
                    var bounded = worded("The pinned file has no @Min, clamp or other check on the path.")
                            .apply(candidate);
                    ((ObjectNode) bounded.path("evidence").path("search"))
                            .put("boundary", "the changed file; no exception handler exists in the repository");
                    return bounded;
                }))
                .record(job);

        assertWithheld(CandidateRefusal.CONSULTED_SOURCE_UNREAD, WORDED + UNSEARCHED);
    }

    @Test
    void shouldAdmitWordsAboutTheCitedLinesWithoutASearch() {
        pr50Capture(SourceCompleteness.COMPLETE);

        service.prepare(
                job,
                pr50ErrorHandling(List.of(), worded("The pinned file has no @Min, clamp or other check on the path.")));
        verify(membership, never()).withhold(anyLong(), any(), any(), any());
    }

    @Test
    void shouldLeaveASourceTheCatalogDoesNotKnowToTheVerifierSoItWithholdsOnlyThatPractice() {
        pr50Capture(SourceCompleteness.COMPLETE);
        var unknown = new SourceKind("scm.repository.files");
        doThrow(new CandidateEvidenceVerifier.Unverified(
                        CandidateRefusal.SOURCE_UNAVAILABLE, "A warrant names a source that was not captured", null))
                .when(verifier)
                .verifyCandidate(eq(job), any(), eq(ReviewKind.ITEM), any(), any());
        when(results.admit(1, job.getReviewId(), job.getId(), 0, List.of()))
                .thenReturn(new AdmissionReceipt(List.of(), List.of()));

        service.prepare(job, pr50ErrorHandling(List.of(), candidate -> {
                    ((ObjectNode) candidate.path("evidence").path("search"))
                            .set("consulted", mapper.valueToTree(List.of(unknown.value())));
                    return candidate;
                }))
                .record(job);

        verify(membership)
                .withhold(
                        1,
                        RuntimeReviewTestData.assignment(5L, null).id(),
                        CandidateRefusal.SOURCE_UNAVAILABLE,
                        "A warrant names a source that was not captured");
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
