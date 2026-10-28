package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.FolderSource;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.handler.candidate.Candidate;
import de.tum.cit.aet.hephaestus.agent.handler.candidate.CandidateParser;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.evidence.CapturedSource;
import de.tum.cit.aet.hephaestus.evidence.CitationCapabilities;
import de.tum.cit.aet.hephaestus.evidence.CitationRequirement;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCatalog;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceContract;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.CitationCheck;
import de.tum.cit.aet.hephaestus.integration.core.spi.CitationRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.CitationVerifier;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CandidateEvidenceVerifierTest extends BaseUnitTest {
    private static final JsonMapper MAPPER = new JsonMapper();
    private static final SourceKind SOURCE = new SourceKind("docs.document.core");
    private static final ItemKind KIND = new ItemKind("docs.document");
    private static final String TEXT = "Before\nExact quote\nAfter\n";
    private final ReviewJob job = new ReviewJob();
    private final JobEvidenceFiles files = mock(JobEvidenceFiles.class);
    private final ClasspathSourceCatalogRegistry catalogs = mock(ClasspathSourceCatalogRegistry.class);
    private final CitationVerifier domain = mock(CitationVerifier.class);

    private CandidateEvidenceVerifier verifier() {
        job.setWorkspaceId(1);
        var policy = mock(SourceContract.class);
        var catalog = mock(SourceCatalog.class);
        when(catalogs.current()).thenReturn(catalog);
        when(catalog.source(SOURCE)).thenReturn(Optional.of(policy));
        when(policy.appliesTo(KIND.value())).thenReturn(true);
        when(policy.citation())
                .thenReturn(new CitationCapabilities(CitationRequirement.FORBIDDEN, CitationRequirement.FORBIDDEN));
        when(catalogs.isSourceUsePermitted(SOURCE, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .thenReturn(true);
        when(domain.kinds()).thenReturn(Set.of(KIND));
        return new CandidateEvidenceVerifier(files, catalogs, List.of(domain));
    }

    private CandidateEvidenceVerifier verifier(String text) {
        var verifier = verifier();
        when(files.readText(eq(job), eq("context/body.txt"), any())).thenReturn(Optional.of(text));
        return verifier;
    }

    private ReviewAssignmentSnapshot assignment() {
        var item = new ItemRef(1, 2, KIND, 20L, null, null);
        var practice = RuntimeReviewTestData.assignment(null, null).practice();
        return new ReviewAssignmentSnapshot(
                new ReviewAssignmentId(UUID.randomUUID(), 31, 2),
                item,
                new ItemVersion("docs:one", null),
                null,
                "one",
                practice);
    }

    private CapturedEvidence captured(boolean registered) {
        return captured(TEXT, registered);
    }

    private CapturedEvidence captured(String text, boolean registered) {
        String digest = ProvenanceDigest.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
        var file = new CapturedSource(
                "context/body.txt", "text/plain", digest, text.getBytes(StandardCharsets.UTF_8).length);
        var now = Instant.now();
        var state = new SourceCaptureState.Available(
                SourceContentState.NON_EMPTY,
                SourceCompleteness.COMPLETE,
                new SourceCaptureFacts(now, null, null, "docs:one"),
                List.of());
        return CapturedEvidence.of(new JobFolderIndex(
                new SourceContractVersion("3.0.0"),
                "a".repeat(64),
                KIND.value(),
                now,
                List.of(new SourceCapture(SOURCE, state, EvidenceSnapshotFixtures.citation(SOURCE), List.of(file))),
                List.of(),
                List.of(new FolderSource(SOURCE, file)),
                registered
                        ? List.of(new CitableText(
                                SOURCE.value(), "document/body", CitationSide.NEW, null, "context/body.txt", digest))
                        : List.of()));
    }

    private Candidate candidate() {
        return CandidateParser.parse(MAPPER.readTree("""
            {"outcome":"MET","summary":"The practice is met","explanation":"The captured body supports the criterion",
             "citations":[{"sourceKind":"docs.document.core","sourcePath":"context/body.txt","path":"document/body",
             "startLine":999,"endLine":999,"quote":"Exact quote"}],
             "evidence":{},"coverage":{"source":"host","reads":1,"truncatedReads":0,"failedReads":0,"receipts":[],"complete":true}}
            """));
    }

    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    private static final String README = "# Retry\nBoth inputs must be positive whole numbers.\n";
    private static final String SMOKE = "final class RetryPolicySmokeTest {}\n";
    private static final String CHANGE = "{\"diff\":\"+Both inputs must be positive whole numbers.\"}";
    private static final String RANGE = "+Both inputs must be positive whole numbers.";
    private static final String README_TEXT = "context/citable/new/README.md";
    private static final String SMOKE_TEXT = "context/citable/new/src/test/RetryPolicySmokeTest.java";
    private static final String CHANGE_RECORD = "context/change.json";
    private static final String RANGE_TEXT = "context/citable-records/scm.pull-request.diff/1111.txt";

    private static String digest(String text) {
        return ProvenanceDigest.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    private CandidateEvidenceVerifier diffVerifier() {
        job.setWorkspaceId(1);
        var policy = mock(SourceContract.class);
        var catalog = mock(SourceCatalog.class);
        when(catalogs.current()).thenReturn(catalog);
        when(catalog.source(DIFF)).thenReturn(Optional.of(policy));
        when(policy.appliesTo(KIND.value())).thenReturn(true);
        when(policy.citation())
                .thenReturn(new CitationCapabilities(CitationRequirement.OPTIONAL, CitationRequirement.OPTIONAL));
        when(catalogs.isSourceUsePermitted(DIFF, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .thenReturn(true);
        when(domain.kinds()).thenReturn(Set.of(KIND));
        when(files.readText(job, README_TEXT, digest(README))).thenReturn(Optional.of(README));
        when(files.readText(job, SMOKE_TEXT, digest(SMOKE))).thenReturn(Optional.of(SMOKE));
        when(files.readText(job, RANGE_TEXT, digest(RANGE))).thenReturn(Optional.of(RANGE));
        return new CandidateEvidenceVerifier(files, catalogs, List.of(domain));
    }

    /** A change capture as the server stages it: the change record and several diff texts of one kind. */
    private CapturedEvidence changeCapture(boolean smokeFirst, String readmeDigest, CitableText... extra) {
        return changeCapture(smokeFirst, readmeDigest, List.of(), extra);
    }

    private CapturedEvidence changeCapture(
            boolean smokeFirst, String readmeDigest, List<CapturedSource> extraFiles, CitableText... extra) {
        var now = Instant.now();
        var state = new SourceCaptureState.Available(
                SourceContentState.NON_EMPTY,
                SourceCompleteness.COMPLETE,
                new SourceCaptureFacts(now, null, null, "docs:one"),
                List.of());
        var smoke = new CapturedSource(SMOKE_TEXT, "text/plain", digest(SMOKE), SMOKE.length());
        var readme = new CapturedSource(README_TEXT, "text/plain", readmeDigest, README.length());
        var change = new CapturedSource(CHANGE_RECORD, "application/json", digest(CHANGE), CHANGE.length());
        var range = new CapturedSource(RANGE_TEXT, "text/plain", digest(RANGE), RANGE.length());
        var witness = new CapturedSource("context/diff-witness.json", "application/json", digest("{}"), 2);
        var captured = new ArrayList<>(extraFiles);
        captured.addAll(
                smokeFirst
                        ? List.of(smoke, readme, change, range, witness)
                        : List.of(witness, range, change, readme, smoke));
        var texts = new ArrayList<>(List.of(
                new CitableText(
                        DIFF.value(),
                        "src/test/RetryPolicySmokeTest.java",
                        CitationSide.NEW,
                        null,
                        SMOKE_TEXT,
                        digest(SMOKE)),
                new CitableText(DIFF.value(), "README.md", CitationSide.NEW, null, README_TEXT, readmeDigest),
                new CitableText(DIFF.value(), CHANGE_RECORD, CitationSide.NEW, null, CHANGE_RECORD, digest(CHANGE)),
                new CitableText(
                        DIFF.value(), CHANGE_RECORD + "#/diff", CitationSide.NEW, null, RANGE_TEXT, digest(RANGE))));
        texts.addAll(List.of(extra));
        return CapturedEvidence.of(new JobFolderIndex(
                new SourceContractVersion("3.0.0"),
                "a".repeat(64),
                KIND.value(),
                now,
                List.of(new SourceCapture(DIFF, state, EvidenceSnapshotFixtures.citation(DIFF), captured)),
                List.of(),
                captured.stream().map(file -> new FolderSource(DIFF, file)).toList(),
                List.copyOf(texts)));
    }

    private Candidate diffCandidate(String sourcePath, String path, String side, String quote) {
        return diffCandidate(sourcePath, path, side, quote, "");
    }

    private Candidate diffCandidate(String sourcePath, String path, String side, String quote, String commit) {
        return CandidateParser.parse(MAPPER.readTree("""
            {"outcome":"MET","summary":"The practice is met","explanation":"The captured change supports the criterion",
             "citations":[{"sourceKind":"scm.pull-request.diff","sourcePath":"%s","path":"%s","side":"%s",%s
             "startLine":2,"endLine":2,"quote":"%s"}],
             "evidence":{},"coverage":{"source":"host","reads":1,"truncatedReads":0,"failedReads":0,"receipts":[],"complete":true}}
            """.formatted(
                        sourcePath, path, side, commit.isEmpty() ? "" : "\"commit\":\"" + commit + "\",", quote)));
    }

    private EvidenceCitation verifiedDiff(Candidate candidate, CapturedEvidence captured) {
        return diffVerifier()
                .verifyCandidate(job, assignment(), ReviewKind.ITEM, candidate, captured)
                .citations()
                .getFirst();
    }

    private CandidateRefusal diffRefusal(Candidate candidate, CapturedEvidence captured) {
        var verifier = diffVerifier();
        try {
            verifier.verifyCandidate(job, assignment(), ReviewKind.ITEM, candidate, captured);
        } catch (CandidateEvidenceVerifier.Unverified failure) {
            return failure.refusal();
        }
        throw new AssertionError("The citation was verified");
    }

    @Test
    void shouldStoreTheVerifiedTextWhenADiffCitationNamesAnotherTextOfItsKind() {
        for (boolean smokeFirst : new boolean[] {true, false}) {
            var stored = verifiedDiff(
                    diffCandidate(SMOKE_TEXT, "README.md", "NEW", "Both inputs must be positive whole numbers."),
                    changeCapture(smokeFirst, digest(README)));

            assertThat(stored.sourcePath()).isEqualTo(README_TEXT);
            assertThat(stored.path()).isEqualTo("README.md");
            assertThat(Objects.requireNonNull(stored.verification()).itemSha256())
                    .isEqualTo(digest(README));
        }
        verify(files, atLeastOnce()).readText(job, README_TEXT, digest(README));
        verify(files, never()).readText(eq(job), eq(SMOKE_TEXT), any());
    }

    @Test
    void shouldStoreTheVerifiedTextWhenADiffCitationNamesTheChangeRecord() {
        var stored = verifiedDiff(
                diffCandidate(CHANGE_RECORD, "README.md", "NEW", "Both inputs must be positive whole numbers."),
                changeCapture(true, digest(README)));

        assertThat(stored.sourcePath()).isEqualTo(README_TEXT);
        assertThat(Objects.requireNonNull(stored.verification()).itemSha256()).isEqualTo(digest(README));
    }

    @Test
    void shouldKeepTheChangeRecordForACitationOfItsOwnRangeAndAWitnessThatHoldsNoText() {
        String rangeQuote = "+Both inputs must be positive whole numbers.";
        assertThat(verifiedDiff(
                                diffCandidate(SMOKE_TEXT, CHANGE_RECORD + "#/diff", "NEW", rangeQuote),
                                changeCapture(true, digest(README)))
                        .sourcePath())
                .isEqualTo(CHANGE_RECORD);
        assertThat(verifiedDiff(
                                diffCandidate(CHANGE_RECORD, CHANGE_RECORD + "#/diff", "NEW", rangeQuote),
                                changeCapture(false, digest(README)))
                        .sourcePath())
                .isEqualTo(CHANGE_RECORD);
        assertThat(verifiedDiff(
                                diffCandidate(
                                        "context/diff-witness.json",
                                        "README.md",
                                        "NEW",
                                        "Both inputs must be positive whole numbers."),
                                changeCapture(true, digest(README)))
                        .sourcePath())
                .isEqualTo("context/diff-witness.json");
    }

    @Test
    void shouldReadALiteralHashInAChangedFileNameAsPartOfTheNameNotARecordFragment() {
        String part = "final class Part {}\n";
        String partText = "context/citable/new/src/a#part.java";
        String prefix = "a\n";
        when(files.readText(job, partText, digest(part))).thenReturn(Optional.of(part));
        var captured = changeCapture(
                true,
                digest(README),
                List.of(
                        new CapturedSource(partText, "text/plain", digest(part), part.length()),
                        new CapturedSource("src/a", "text/plain", digest(prefix), prefix.length())),
                new CitableText(DIFF.value(), "src/a#part.java", CitationSide.NEW, null, partText, digest(part)),
                new CitableText(DIFF.value(), "src/a", CitationSide.NEW, null, "src/a", digest(prefix)));
        for (String given : List.of(SMOKE_TEXT, "src/a", partText)) {
            var stored = verifiedDiff(diffCandidate(given, "src/a#part.java", "NEW", "final class Part {}"), captured);

            assertThat(stored.sourcePath()).isEqualTo(partText);
            assertThat(Objects.requireNonNull(stored.verification()).itemSha256())
                    .isEqualTo(digest(part));
        }
        verify(files, never()).readText(eq(job), eq("src/a"), any());
    }

    @Test
    void shouldStillRefuseAWrongQuoteDigestSideCommitPathOrAmbiguousText() {
        String quote = "Both inputs must be positive whole numbers.";
        assertThat(diffRefusal(
                        diffCandidate(SMOKE_TEXT, "README.md", "NEW", "final class RetryPolicySmokeTest {}"),
                        changeCapture(true, digest(README))))
                .isEqualTo(CandidateRefusal.QUOTE_UNVERIFIED);
        assertThat(diffRefusal(
                        diffCandidate(SMOKE_TEXT, "README.md", "NEW", quote), changeCapture(true, "d".repeat(64))))
                .isEqualTo(CandidateRefusal.SOURCE_UNAVAILABLE);
        assertThat(diffRefusal(
                        diffCandidate(SMOKE_TEXT, "README.md", "OLD", quote), changeCapture(true, digest(README))))
                .isEqualTo(CandidateRefusal.SOURCE_UNAVAILABLE);
        assertThat(diffRefusal(
                        diffCandidate(SMOKE_TEXT, "README.md", "NEW", quote, "e".repeat(40)),
                        changeCapture(true, digest(README))))
                .isEqualTo(CandidateRefusal.SOURCE_UNAVAILABLE);
        assertThat(diffRefusal(
                        diffCandidate("context/not-captured.json", "README.md", "NEW", quote),
                        changeCapture(true, digest(README))))
                .isEqualTo(CandidateRefusal.SOURCE_UNAVAILABLE);
        assertThat(diffRefusal(
                        diffCandidate(SMOKE_TEXT, "README.md", "NEW", quote),
                        changeCapture(
                                true,
                                digest(README),
                                new CitableText(
                                        DIFF.value(), "README.md", CitationSide.NEW, null, SMOKE_TEXT, digest(SMOKE)))))
                .isEqualTo(CandidateRefusal.SOURCE_UNAVAILABLE);
    }

    @Test
    void derivesLinesFromTheRegisteredTextBeforeDelegatingToTheDomain() {
        var verifier = verifier(TEXT);
        when(files.verificationDirectory(job))
                .thenReturn(new EvidenceDirectory("inputs/", Path.of("/tmp/citation-test")));
        var candidate = candidate();
        var verified = verifier.verifyCandidate(job, assignment(), ReviewKind.ITEM, candidate, captured(true));
        assertThat(verified.citations().getFirst().startLine()).isEqualTo(2);
        assertThat(verified.citations().getFirst().verification()).isNotNull();
        assertThat(candidate.citations().getFirst().startLine()).isEqualTo(999);
        var check = ArgumentCaptor.forClass(CitationCheck.class);
        verify(domain).verify(check.capture());
        assertThat(check.getValue().citations().getFirst().startLine()).isEqualTo(2);
    }

    @Test
    void shouldRefuseAnExampleAtTheProblemWhenDeclaredLinesHideTheOverlap() {
        var verifier = verifier(TEXT);
        var candidate = CandidateParser.parse(MAPPER.readTree("""
            {"outcome":"NOT_MET","summary":"A problem","explanation":"The body has a problem","citations":[],
             "problems":[{"severity":"MINOR","summary":"A problem","explanation":"The body has a problem",
              "citations":[{"sourceKind":"docs.document.core","sourcePath":"context/body.txt","path":"document/body",
                "startLine":1,"endLine":1,"quote":"Exact quote"}],"basis":{"type":"duty","criterionQuote":"Use clear names.","event":0,"explanation":"The body is the witness"}}],
             "example":{"citation":{"sourceKind":"docs.document.core","sourcePath":"context/body.txt","path":"document/body",
                "startLine":999,"endLine":999,"quote":"Exact quote"},"note":"The same quote is called an example"},
             "evidence":{},"coverage":{"source":"host","reads":1,"truncatedReads":0,"failedReads":0,"receipts":[],"complete":true}}
            """));
        assertThatThrownBy(
                        () -> verifier.verifyCandidate(job, assignment(), ReviewKind.ITEM, candidate, captured(true)))
                .isInstanceOf(CandidateEvidenceVerifier.Unverified.class)
                .satisfies(failure -> assertThat(((CandidateEvidenceVerifier.Unverified) failure).refusal())
                        .isEqualTo(CandidateRefusal.EXAMPLE_OVERLAPS_PROBLEM));
    }

    @Test
    void shouldAcceptSeparateQuotesWhenTheirClaimedLinesOverlap() {
        var verifier = verifier(TEXT);
        var candidate = CandidateParser.parse(MAPPER.readTree("""
            {"outcome":"NOT_MET","summary":"A problem","explanation":"The body has a problem","citations":[],
             "problems":[{"severity":"MINOR","summary":"A problem","explanation":"The body has a problem",
              "citations":[{"sourceKind":"docs.document.core","sourcePath":"context/body.txt","path":"document/body",
                "startLine":999,"endLine":999,"quote":"Exact quote"}],
                "basis":{"type":"duty","criterionQuote":"Use clear names.","event":0,"explanation":"The body is the witness"}}],
             "example":{"citation":{"sourceKind":"docs.document.core","sourcePath":"context/body.txt","path":"document/body",
                "startLine":999,"endLine":999,"quote":"After"},"note":"A separate example"},
             "evidence":{},"coverage":{"source":"host","reads":1,"truncatedReads":0,"failedReads":0,"receipts":[],"complete":true}}
            """));
        var verified = verifier.verifyCandidate(job, assignment(), ReviewKind.ITEM, candidate, captured(true));
        assertThat(verified.problems().getFirst().citations().getFirst().startLine())
                .isEqualTo(2);
        assertThat(Objects.requireNonNull(verified.example()).citation().startLine())
                .isEqualTo(3);
    }

    @Test
    void shouldRefuseAnUnregisteredQuoteBeforeReadingCapturedBytes() {
        var verifier = verifier();
        assertThatThrownBy(() ->
                        verifier.verifyCandidate(job, assignment(), ReviewKind.ITEM, candidate(), captured(false)))
                .isInstanceOf(CandidateEvidenceVerifier.Unverified.class);
    }

    @Test
    void shouldMatchTheSharedCoordinateContract() throws Exception {
        try (var input =
                Objects.requireNonNull(getClass().getResourceAsStream("/agent/citation-coordinate-cases.json"))) {
            for (var fixture : MAPPER.readTree(input)) {
                var verifier = verifier(fixture.path("content").asString());
                var raw = (ObjectNode) MAPPER.readTree("""
                    {"outcome":"MET","summary":"Clear names","explanation":"The text supports the criterion",
                    "citations":[{"sourceKind":"docs.document.core","sourcePath":"context/body.txt","path":"document/body",
                    "startLine":1,"endLine":1,"quote":"Exact quote"}],"evidence":{},
                    "coverage":{"source":"host","reads":1,"truncatedReads":0,"failedReads":0,"receipts":[],"complete":true}}
                    """);
                var citation = (ObjectNode) raw.path("citations").get(0);
                citation.set("startLine", fixture.path("startLine"));
                citation.set("endLine", fixture.path("endLine"));
                citation.set("quote", fixture.path("quote"));
                var candidate = CandidateParser.parse(raw);
                if (fixture.path("expectedStartLine").isNull()) {
                    assertThatThrownBy(() -> verifier.verifyCandidate(
                                    job,
                                    assignment(),
                                    ReviewKind.ITEM,
                                    candidate,
                                    captured(fixture.path("content").asString(), true)))
                            .isInstanceOf(CandidateEvidenceVerifier.Unverified.class);
                } else {
                    var verified = verifier.verifyCandidate(
                            job,
                            assignment(),
                            ReviewKind.ITEM,
                            candidate,
                            captured(fixture.path("content").asString(), true));
                    assertThat(verified.citations().getFirst().startLine())
                            .as(fixture.path("name").asString())
                            .isEqualTo(fixture.path("expectedStartLine").asInt());
                }
            }
        }
    }

    @Test
    void shouldRefuseAnAmbiguousQuoteWhenCapturedTextRepeatsIt() {
        var verifier = verifier(TEXT + "Exact quote");
        assertThatThrownBy(
                        () -> verifier.verifyCandidate(job, assignment(), ReviewKind.ITEM, candidate(), captured(true)))
                .isInstanceOf(CandidateEvidenceVerifier.Unverified.class);
    }

    @Test
    void keepsTheDomainsVersionRefusalDistinctFromGenericQuoteProof() {
        var verifier = verifier(TEXT);
        when(files.verificationDirectory(job))
                .thenReturn(new EvidenceDirectory("inputs/", Path.of("/tmp/citation-test")));
        when(domain.verify(any()))
                .thenReturn(List.of(
                        new CitationRefusal(0, CandidateRefusal.BASIS_REVISION_UNSUPPORTED, "Another pinned version")));
        assertThatThrownBy(
                        () -> verifier.verifyCandidate(job, assignment(), ReviewKind.ITEM, candidate(), captured(true)))
                .isInstanceOfSatisfying(
                        CandidateEvidenceVerifier.Unverified.class,
                        refusal ->
                                assertThat(refusal.refusal()).isEqualTo(CandidateRefusal.BASIS_REVISION_UNSUPPORTED));
    }

    @Test
    void shouldRecordSourceUnavailableWithoutClaimingTheQuoteIsFalse() {
        var verifier = verifier();
        when(files.readText(eq(job), eq("context/body.txt"), any())).thenReturn(Optional.empty());
        assertThatThrownBy(
                        () -> verifier.verifyCandidate(job, assignment(), ReviewKind.ITEM, candidate(), captured(true)))
                .isInstanceOfSatisfying(
                        CandidateEvidenceVerifier.Unverified.class,
                        refusal -> assertThat(refusal.refusal()).isEqualTo(CandidateRefusal.SOURCE_UNAVAILABLE));
    }

    private Candidate problem(String basis) {
        return CandidateParser.parse(MAPPER.readTree("""
            {"outcome":"NOT_MET","summary":"A document problem","explanation":"The captured words need a change",
             "citations":[],"problems":[{"key":"document-problem","severity":"MAJOR","summary":"Improve the text",
               "explanation":"The words do not meet the criterion","nextStep":"Rewrite the sentence",
               "citations":[{"sourceKind":"docs.document.core","sourcePath":"context/body.txt","path":"document/body",
                 "startLine":2,"endLine":2,"quote":"Exact quote"}],"basis":%s}],"evidence":{},
             "coverage":{"source":"host","reads":1,"truncatedReads":0,"failedReads":0,"receipts":[],"complete":true}}
            """.formatted(basis)));
    }

    @Test
    void shouldNameAMissingStateWitnessWithoutCallingItAnotherVersion() {
        var verifier = verifier(TEXT);
        assertThatThrownBy(() -> verifier.verifyCandidate(
                        job,
                        assignment(),
                        ReviewKind.SCAN,
                        problem("{\"type\":\"state\",\"explanation\":\"The current file contains these words\"}"),
                        captured(true)))
                .isInstanceOfSatisfying(
                        CandidateEvidenceVerifier.Unverified.class,
                        refusal -> assertThat(refusal.refusal()).isEqualTo(CandidateRefusal.BASIS_WITNESS_MISSING));
    }

    @Test
    void shouldNameAnUnverifiedDutyQuoteWithoutCallingItAnOutcomeMismatch() {
        var verifier = verifier(TEXT);
        assertThatThrownBy(() -> verifier.verifyCandidate(
                        job,
                        RuntimeReviewTestData.assignment(5L, null),
                        ReviewKind.ITEM,
                        problem("{\"type\":\"duty\",\"criterionQuote\":\"Another required"
                                + " duty\",\"event\":0,\"explanation\":\"The document must meet this duty\"}"),
                        captured(true)))
                .isInstanceOfSatisfying(
                        CandidateEvidenceVerifier.Unverified.class,
                        refusal -> assertThat(refusal.refusal()).isEqualTo(CandidateRefusal.DUTY_QUOTE_UNVERIFIED));
    }

    @Test
    void shouldRefuseAnUnknownSourceForItsCandidateInsteadOfFailingTheEnvelope() {
        var catalog = mock(SourceCatalog.class);
        when(catalogs.current()).thenReturn(catalog);
        when(catalog.source(SOURCE)).thenReturn(Optional.empty());
        var verifier = new CandidateEvidenceVerifier(files, catalogs, List.of());
        assertThatThrownBy(
                        () -> verifier.verifyCandidate(job, assignment(), ReviewKind.ITEM, candidate(), captured(true)))
                .isInstanceOfSatisfying(
                        CandidateEvidenceVerifier.Unverified.class,
                        refusal -> assertThat(refusal.refusal()).isEqualTo(CandidateRefusal.MALFORMED_CANDIDATE));
    }
}
