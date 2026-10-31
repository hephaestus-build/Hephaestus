package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SubmittedResultDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmCitationVerifier;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.ScanDefinition;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceiptStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ScanAdmissionTest extends BaseUnitTest {
    private static final SourceKind SOURCE = new SourceKind("scm.repository.tree");
    private static final ItemKind KIND = new ItemKind("scm.file");
    private static final String COMMIT = "c".repeat(40);
    private static final String ASSIGNED = "src/Assigned.java";
    private static final String CONTEXT = "docs/Context.md";
    private static final String WITNESS = "repository/HEAD";
    private static final String CONTENT = "class Assigned {}\n";
    private static final String DIGEST = digest(CONTENT);
    private final JsonMapper mapper = new JsonMapper();
    private final Clock clock = Clock.systemUTC();
    private final ClasspathSourceCatalogRegistry catalogs = new ClasspathSourceCatalogRegistry(mapper, clock);
    private final ReviewJobAssignments assignments = mock(ReviewJobAssignments.class);
    private final ResultStore results = mock(ResultStore.class);
    private List<ResultCandidate> staged = List.of();
    private final ReviewAssignmentStore membership = mock(ReviewAssignmentStore.class);
    private final EvidenceFolderPersonDataCatalog copies = mock(EvidenceFolderPersonDataCatalog.class);
    private @Nullable PreparedJobInputs inputs;
    private final ReviewJob job = RuntimeReviewTestData.job();

    @TempDir
    Path root;

    private static String digest(String text) {
        return ProvenanceDigest.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    private ReviewAssignmentSnapshot assignment() {
        var original = RuntimeReviewTestData.assignment(null, null);
        var practice = original.practice();
        return new ReviewAssignmentSnapshot(
                original.id(),
                new ItemRef(1, 2, KIND, null, 10L, ASSIGNED),
                new ItemVersion("scm.file:v1:" + "b".repeat(40) + ":" + DIGEST, COMMIT),
                null,
                original.practiceSlug(),
                new PinnedPractice(
                        practice.practiceId(),
                        practice.practiceVersionId(),
                        scanned(DefinitionTestFixtures.definition(KIND, "Clear names", "Use clear names.", null)),
                        practice.reviewDigest(),
                        practice.method()));
    }

    /** The practice as a scan runs it: its scan settings read the repository files. */
    private static PracticeDefinition scanned(PracticeDefinition original) {
        return new PracticeDefinition(
                original.name(),
                original.reviewOn(),
                original.appliesWhen(),
                original.about(),
                original.reads(),
                original.reviewedBy(),
                original.criteria(),
                original.script(),
                new ScanDefinition(
                        KIND,
                        List.of("**/*"),
                        List.of(),
                        List.of("**/*"),
                        new PracticeReads("3.0.0", List.of(new PracticeReadSource(SOURCE, ReadNeed.REQUIRED)))),
                original.lineNotes(),
                original.guidance());
    }

    private JobEvidenceFiles files() {
        return new JobEvidenceFiles(new FabricLayout(root.toString()), mock(ReviewJobRepository.class), clock, copies);
    }

    @BeforeEach
    void setup() {
        job.setWorkerId("worker");
        when(copies.finishCapture(job)).thenReturn(() -> {});
        var assigned = assignment();
        when(assignments.admissible(job)).thenReturn(List.of(assigned));
        when(assignments.review(job)).thenReturn(RuntimeReviewTestData.review(ReviewKind.SCAN, assigned));

        var capture = EvidenceSnapshotFixtures.snapshot(mapper, KIND.value());
        var source = EvidenceSnapshotFixtures.availableSource(capture, SOURCE.value(), COMMIT);
        EvidenceSnapshotFixtures.file(capture, source, WITNESS, digest("Pinned tree\n"));
        capture.withObject("manifest")
                .set(
                        "citableTexts",
                        mapper.valueToTree(List.of(
                                new CitableText(
                                        SOURCE.value(),
                                        ASSIGNED,
                                        CitationSide.NEW,
                                        COMMIT,
                                        "repository/" + ASSIGNED,
                                        DIGEST),
                                new CitableText(
                                        SOURCE.value(),
                                        CONTEXT,
                                        CitationSide.NEW,
                                        COMMIT,
                                        "repository/" + CONTEXT,
                                        digest("Clear names\n")))));
        job.setCapture(capture);
        inputs = files().prepare(
                        job,
                        new PreparedEvidence(
                                Map.of(
                                        WITNESS,
                                        "Pinned tree\n".getBytes(StandardCharsets.UTF_8),
                                        "repository/" + ASSIGNED,
                                        CONTENT.getBytes(StandardCharsets.UTF_8),
                                        "repository/" + CONTEXT,
                                        "Clear names\n".getBytes(StandardCharsets.UTF_8)),
                                null),
                        null);
    }

    @AfterEach
    void cleanup() {
        if (inputs != null) inputs.close();
    }

    private ReadReceipt receipt(String path, String version, ReadReceiptStatus status, @Nullable String contentDigest) {
        return new ReadReceipt(SOURCE, WITNESS, path, version, status, contentDigest, null, null, null);
    }

    private ObjectNode candidate(List<ReadReceipt> receipts) {
        var candidate = mapper.createObjectNode();
        candidate.put("outcome", "MET").put("summary", "Clear names").put("explanation", "The names explain the file.");
        candidate
                .putArray("citations")
                .addObject()
                .put("sourceKind", SOURCE.value())
                .put("sourcePath", WITNESS)
                .put("path", CONTEXT)
                .put("commit", COMMIT)
                .put("startLine", 1)
                .put("endLine", 1)
                .put("quote", "Clear names");
        candidate.putObject("evidence");
        var coverage = candidate
                .putObject("coverage")
                .put("source", "host")
                .put("complete", true)
                .put("reads", receipts.size())
                .put("truncatedReads", 0)
                .put("failedReads", 0);
        coverage.set("receipts", mapper.valueToTree(receipts));
        return candidate;
    }

    private void admit(ObjectNode candidate, List<ReadReceipt> receipts) {
        var files = files();
        var verifier = new CandidateEvidenceVerifier(files, catalogs, List.of(new ScmCitationVerifier(mapper)));
        var service = new ReviewOutputService(assignments, verifier, results, mapper, files, catalogs, membership);
        var assigned = assignment();
        var entry = new SubmittedResultDTO(
                assigned.practiceSlug(), "2", assigned.version().value(), candidate, receipts);
        service.prepareStage(
                        job,
                        new AdmissionRequestDTO(3, job.getReviewId(), job.getId(), 0, List.of(entry), List.of()),
                        (owned, candidates) -> staged = List.copyOf(candidates))
                .record(job);
    }

    private List<ResultCandidate> admitted() {
        verifyNoInteractions(results);
        return staged;
    }

    private void assertWithheld(CandidateRefusal refusal) {
        assertThat(admitted()).isEmpty();
        assertThat(Objects.requireNonNull(job.getCapture()).path("withheld"))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.path("practiceSlug").asString())
                            .isEqualTo(assignment().practiceSlug());
                    assertThat(entry.path("reason").asString()).isEqualTo(refusal.wireValue());
                });
    }

    @Test
    void shouldWithholdScanMetWhenCoverageIsClaimed() {
        var candidate = candidate(List.of());
        candidate.set("coverage", mapper.readTree("""
            {"source":"claimed","complete":true,"selected":1,"checked":1,"total":1}
            """));
        admit(candidate, List.of());
        assertWithheld(CandidateRefusal.COVERAGE_INCONSISTENT);
    }

    @Test
    void shouldWithholdScanMetWhenNoReadReceiptWasSubmitted() {
        admit(candidate(List.of()), List.of());
        assertWithheld(CandidateRefusal.COVERAGE_INCONSISTENT);
    }

    @Test
    void shouldWithholdScanMetWhenOnlyContextWasReadCompletely() {
        var receipts = List.of(receipt(CONTEXT, COMMIT, ReadReceiptStatus.COMPLETE, digest("Clear names\n")));
        admit(candidate(receipts), receipts);
        assertWithheld(CandidateRefusal.COVERAGE_INCONSISTENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRUNCATED", "FAILED", "BINARY", "ABSENT"})
    void shouldWithholdScanMetWhenTheAssignedReadIsNotComplete(String status) {
        var receipts = List.of(receipt(ASSIGNED, COMMIT, ReadReceiptStatus.valueOf(status), DIGEST));
        admit(candidate(receipts), receipts);
        assertWithheld(CandidateRefusal.COVERAGE_INCONSISTENT);
    }

    @Test
    void shouldWithholdScanMetWhenTheAssignedReadUsesAnotherCommit() {
        var receipts = List.of(receipt(ASSIGNED, "d".repeat(40), ReadReceiptStatus.COMPLETE, DIGEST));
        admit(candidate(receipts), receipts);
        assertWithheld(CandidateRefusal.COVERAGE_INCONSISTENT);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void shouldWithholdScanMetWhenTheReadDigestCannotBeVerified(@Nullable String contentDigest) {
        var receipts = List.of(receipt(ASSIGNED, COMMIT, ReadReceiptStatus.COMPLETE, contentDigest));
        admit(candidate(receipts), receipts);
        assertWithheld(CandidateRefusal.COVERAGE_INCONSISTENT);
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {"invalid"})
    void shouldWithholdMalformedReadDigestsBeforeCheckingScanCoverage(String contentDigest) {
        var receipts = List.of(receipt(ASSIGNED, COMMIT, ReadReceiptStatus.COMPLETE, contentDigest));
        admit(candidate(receipts), receipts);
        assertWithheld(CandidateRefusal.MALFORMED_CANDIDATE);
    }

    @Test
    void shouldAdmitScanMetWhenTheAssignedPinnedContentWasReadCompletely() {
        var receipts = List.of(receipt(ASSIGNED, COMMIT, ReadReceiptStatus.COMPLETE, DIGEST));
        admit(candidate(receipts), receipts);
        assertThat(admitted()).singleElement().satisfies(result -> {
            assertThat(result.itemId()).isEqualTo(assignment().item().itemId());
            assertThat(result.itemVersion()).isEqualTo(assignment().version().value());
            assertThat(result.outcome()).isEqualTo(ResultOutcome.MET);
        });
        assertThat(Objects.requireNonNull(job.getCapture()).path("withheld")).isEmpty();
    }

    @Test
    void shouldWithholdScanDutyWhenAValidCriterionQuoteCitesOnlyContext() {
        var candidate = candidate(List.of());
        candidate.put("outcome", "NOT_MET");
        var problem = candidate
                .putArray("problems")
                .addObject()
                .put("severity", "MAJOR")
                .put("summary", "Improve the name")
                .put("explanation", "The name does not meet the criterion.");
        problem.set("citations", candidate.get("citations"));
        candidate.putArray("citations");
        problem.putObject("basis")
                .put("type", "duty")
                .put("criterionQuote", "Use clear names.")
                .put("event", 0)
                .put("explanation", "The file must use clear names.");
        admit(candidate, List.of());
        assertWithheld(CandidateRefusal.BASIS_STATE_UNSUPPORTED);
    }
}
