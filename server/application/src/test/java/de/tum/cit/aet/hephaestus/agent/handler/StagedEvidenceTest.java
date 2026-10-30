package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasis;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.practices.spi.CitationProof;
import de.tum.cit.aet.hephaestus.practices.spi.CitationVerificationStatus;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStagingStore.StagedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidence;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class StagedEvidenceTest extends BaseUnitTest {
    private final JsonMapper mapper = new JsonMapper();
    private final UUID reviewId = UUID.randomUUID();
    private final UUID firstCopy = UUID.randomUUID();
    private final UUID secondCopy = UUID.randomUUID();

    @Test
    void shouldKeepBothAttemptsExactTextsWhenTheLaterCaptureReusesThePath() {
        var first = evidence("First captured statement", firstCopy);
        var second = evidence("Second captured statement", secondCopy);
        var manifest = manifest("Second captured statement");
        var basis = new ReviewCompositionBasis();
        basis.setExpiresAt(Instant.now().plusSeconds(3600));
        var payload = mapper.createObjectNode();
        payload.putObject("files")
                .put("INDEX.json", Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(manifest)));
        basis.setPayload(payload);
        StagedEvidence.merge(
                mapper,
                basis,
                List.of(
                        new StagedPractice(
                                new ReviewAssignmentId(reviewId, 31, 2),
                                0,
                                "a".repeat(64),
                                candidate("First captured statement"),
                                first,
                                null),
                        new StagedPractice(
                                new ReviewAssignmentId(reviewId, 41, 2),
                                1,
                                "b".repeat(64),
                                candidate("Second captured statement"),
                                second,
                                null)));
        var files = basis.getPayload().path("files");
        var merged = mapper.readValue(
                Base64.getDecoder().decode(files.path("INDEX.json").asString()), JobFolderIndex.class);
        assertThat(merged.citableTexts()).hasSize(2);
        assertThat(merged.citableTexts())
                .extracting(text -> new String(
                        Base64.getDecoder().decode(files.path(text.stagedPath()).asString()), StandardCharsets.UTF_8))
                .containsExactly("First captured statement", "Second captured statement");
        assertThat(basis.getPayload().path("copies")).hasSize(2);
    }

    @Test
    void shouldRefuseAStageWhenTheRetainedBytesDifferFromTheVerifiedCitation() {
        assertThatThrownBy(() -> StagedEvidence.retain(
                        mapper,
                        manifest("Verified text"),
                        candidate("Verified text"),
                        Map.of("context/body.txt", "Different bytes".getBytes(StandardCharsets.UTF_8)),
                        firstCopy,
                        Instant.now().plusSeconds(60)))
                .isInstanceOf(AdmissionConflictException.class);
    }

    @Test
    void shouldRefuseASealWhenAStagedCitedTextIsCorrupt() {
        var retained = evidence("Verified text", firstCopy);
        retained.withObject("files")
                .properties()
                .forEach(entry -> retained.withObject("files")
                        .put(
                                entry.getKey(),
                                Base64.getEncoder()
                                        .encodeToString("Different bytes".getBytes(StandardCharsets.UTF_8))));
        var basis = new ReviewCompositionBasis();
        basis.setExpiresAt(Instant.now().plusSeconds(3600));
        var payload = mapper.createObjectNode();
        payload.putObject("files")
                .put(
                        "INDEX.json",
                        Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(manifest("Verified text"))));
        basis.setPayload(payload);
        assertThatThrownBy(() -> StagedEvidence.merge(
                        mapper,
                        basis,
                        List.of(new StagedPractice(
                                new ReviewAssignmentId(reviewId, 31, 2),
                                0,
                                "a".repeat(64),
                                candidate("Verified text"),
                                retained,
                                null))))
                .isInstanceOf(AdmissionConflictException.class);
    }

    private ObjectNode evidence(String text, UUID copy) {
        return StagedEvidence.retain(
                mapper,
                manifest(text),
                candidate(text),
                Map.of("context/body.txt", text.getBytes(StandardCharsets.UTF_8)),
                copy,
                Instant.now().plusSeconds(600));
    }

    private JobFolderIndex manifest(String text) {
        var snapshot = EvidenceSnapshotFixtures.snapshot(mapper);
        var source = EvidenceSnapshotFixtures.availableSource(snapshot, "docs.document.core", null);
        String digest = ProvenanceDigest.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
        EvidenceSnapshotFixtures.file(snapshot, source, "context/body.txt", digest);
        snapshot.withObject("manifest")
                .set(
                        "citableTexts",
                        mapper.valueToTree(List.of(new CitableText(
                                "docs.document.core",
                                "document/body",
                                CitationSide.NEW,
                                null,
                                "context/body.txt",
                                digest))));
        return mapper.treeToValue(snapshot.path("manifest"), JobFolderIndex.class);
    }

    private static ResultCandidate candidate(String text) {
        var citation = new EvidenceCitation(
                new SourceKind("docs.document.core"),
                "context/body.txt",
                "document/body",
                null,
                null,
                1,
                1,
                text,
                new CitationProof(
                        CitationVerificationStatus.VERIFIED,
                        ProvenanceDigest.sha256Hex(text.getBytes(StandardCharsets.UTF_8)),
                        ProvenanceDigest.sha256Hex(text.getBytes(StandardCharsets.UTF_8))));
        return new ResultCandidate(
                31,
                32,
                2,
                "docs:one",
                5L,
                ResultOutcome.MET,
                "Clear statement",
                "The statement is clear.",
                new ResultEvidence(List.of(citation), null, null, List.of()),
                null,
                new HostCoverage(true, List.of(), 1, 0, 0, List.of()),
                List.of());
    }
}
