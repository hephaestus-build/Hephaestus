package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.FolderSource;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.handler.candidate.CandidateParser;
import de.tum.cit.aet.hephaestus.agent.handler.composition.PinnedInlineAnchors;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.evidence.CapturedSource;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmCitationVerifier;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class PinnedInlineAnchorsTest {
    private static final String BASE = "b".repeat(40);
    private static final String HEAD = "a".repeat(40);
    private final JsonMapper mapper = new JsonMapper();
    private final PinnedInlineAnchors anchors = new PinnedInlineAnchors(mapper.createObjectNode()
            .put("base_sha", BASE)
            .put("head_sha", HEAD)
            .put("diff", """
                    diff --git a/old.java b/new.java
                    similarity index 80%
                    rename from old.java
                    rename to new.java
                    --- a/old.java
                    +++ b/new.java
                    @@ -3,3 +3,3 @@
                     context
                    -unsafe();
                    +checked();
                     tail
                    """));

    @TempDir
    Path root;

    private EvidenceCitation admitted(String path, String commit, int line) {
        var source = new SourceKind("scm.repository.tree");
        var catalogs = new ClasspathSourceCatalogRegistry(mapper, Clock.systemUTC());
        var contract = catalogs.current().source(source).orElseThrow();
        String statement = commit.equals(BASE) ? "unsafe();" : "checked();";
        String text = "prefix\nprefix\ncontext\n" + statement + "\ntail\n" + "padding\n".repeat(14) + "outside();\n";
        String digest = ProvenanceDigest.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
        var file = new CapturedSource("repos/reviewed/.git/HEAD", "text/plain", digest, text.length());
        var state = new SourceCaptureState.Available(
                SourceContentState.NON_EMPTY,
                SourceCompleteness.COMPLETE,
                new SourceCaptureFacts(Instant.now(), null, null, HEAD),
                List.of());
        var captured = CapturedEvidence.of(new JobFolderIndex(
                catalogs.current().version(),
                catalogs.catalogDigest(),
                "scm.pull_request",
                Instant.now(),
                List.of(new SourceCapture(source, state, contract.citation(), List.of(file))),
                List.of(),
                List.of(new FolderSource(source, file)),
                List.of(new CitableText(source.value(), path, CitationSide.NEW, commit, file.path(), digest))));
        var files = mock(JobEvidenceFiles.class);
        var job = new ReviewJob();
        job.setWorkspaceId(1);
        when(files.readText(eq(job), eq(file.path()), any())).thenReturn(Optional.of(text));
        when(files.verificationDirectory(job)).thenReturn(new EvidenceDirectory("inputs/", root));
        var assignment = new ReviewAssignmentSnapshot(
                new ReviewAssignmentId(UUID.randomUUID(), 31, 2),
                new ItemRef(1, 2, new ItemKind("scm.pull_request"), 20L, null, null),
                ScmVersion.pullRequest(BASE, HEAD, "Title", null),
                null,
                "one",
                RuntimeReviewTestData.assignment(null, null).practice());
        var raw = mapper.createObjectNode()
                .put("outcome", "MET")
                .put("summary", "The practice is met")
                .put("explanation", "Captured code supports the criterion");
        raw.putArray("citations")
                .addObject()
                .put("sourceKind", source.value())
                .put("sourcePath", file.path())
                .put("path", path)
                .put("commit", commit)
                .put("startLine", line)
                .put("endLine", line)
                .put("quote", line == 4 ? statement : line == 20 ? "outside();" : "context");
        raw.putObject("evidence");
        raw.putObject("coverage")
                .put("source", "host")
                .put("reads", 1)
                .put("truncatedReads", 0)
                .put("failedReads", 0)
                .put("complete", true)
                .putArray("receipts");
        var admitted = new CandidateEvidenceVerifier(files, catalogs, List.of(new ScmCitationVerifier(mapper)))
                .verifyCandidate(job, assignment, ReviewKind.ITEM, CandidateParser.parse(raw), captured)
                .citations()
                .getFirst();
        assertThat(admitted.verification()).isNotNull();
        assertThat(admitted.side()).isNull();
        return admitted;
    }

    @Test
    void shouldPlaceAdmittedTreeEvidenceWithoutAddingTheForbiddenSide() {
        var head = admitted("new.java", HEAD, 4);
        assertThat(anchors.permits(head)).isTrue();
        assertThat(anchors.placementSide(head)).isEqualTo(CitationSide.NEW);
        var base = admitted("old.java", BASE, 4);
        assertThat(anchors.permits(base)).isTrue();
        assertThat(anchors.placementSide(base)).isEqualTo(CitationSide.OLD);
        assertThat(anchors.permits(admitted("new.java", "c".repeat(40), 4))).isFalse();
        assertThat(anchors.permits(admitted("old.java", HEAD, 4))).isFalse();
        assertThat(anchors.permits(admitted("new.java", HEAD, 3))).isFalse();
        assertThat(anchors.permits(admitted("untouched.java", HEAD, 4))).isFalse();
        assertThat(anchors.permits(admitted("new.java", HEAD, 20))).isFalse();
    }
}
