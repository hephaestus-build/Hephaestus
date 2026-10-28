package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.spi.BasisToCheck;
import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.CitationCheck;
import de.tum.cit.aet.hephaestus.integration.core.spi.CitationToCheck;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class ScmCitationVerifierTest extends BaseUnitTest {
    private static final String BASE = "a".repeat(40), HEAD = "b".repeat(40);

    @TempDir
    Path root;

    private final ScmCitationVerifier verifier = new ScmCitationVerifier(new ObjectMapper());

    @Test
    void shouldVerifyBothSidesAndTheirChangeBasisAgainstTheCapturedRange() throws Exception {
        range(BASE, HEAD);
        var before = citation(0, CitationSide.OLD, BASE);
        var after = citation(1, CitationSide.NEW, null);
        assertThat(verifier.verify(check(List.of(before, after), List.of(new BasisToCheck(0, "worsened", 1, 0)))))
                .isEmpty();
    }

    @Test
    void shouldRefuseChangedCapturedBaseAtTheSameHead() throws Exception {
        range("c".repeat(40), HEAD);
        assertThat(verifier.verify(check(List.of(citation(0, CitationSide.NEW, HEAD)), List.of())))
                .singleElement()
                .satisfies(
                        refusal -> assertThat(refusal.reason()).isEqualTo(CandidateRefusal.BASIS_REVISION_UNSUPPORTED));
    }

    @Test
    void shouldRefuseAWorsenedBasisWhoseBaseWitnessIsAtTheHead() throws Exception {
        range(BASE, HEAD);
        assertThat(verifier.verify(check(
                        List.of(citation(0, CitationSide.NEW, HEAD), citation(1, CitationSide.NEW, HEAD)),
                        List.of(new BasisToCheck(0, "worsened", 1, 0)))))
                .singleElement()
                .satisfies(refusal -> assertThat(refusal.index()).isEqualTo(1));
    }

    @Test
    void shouldRefuseAnotherRepositoryCommitInAFileScan() {
        var item = new ItemRef(1, 2, ScmItemEvents.FILE, null, 3L, "src/a.ts");
        var citation = new CitationToCheck(0, "scm.repository.tree", "src/a.ts", CitationSide.NEW, BASE, 1, 1, "words");
        assertThat(verifier.verify(new CitationCheck(
                        1,
                        item,
                        new ItemVersion("scm.file:v1:" + "d".repeat(40) + ":" + "e".repeat(64), HEAD),
                        null,
                        new EvidenceDirectory("inputs/", root),
                        List.of(citation),
                        List.of())))
                .hasSize(1);
    }

    @Test
    void shouldRefuseAnExplicitDiffRevisionFromTheOtherSide() throws Exception {
        range(BASE, HEAD);
        assertThat(verifier.verify(check(List.of(citation(0, CitationSide.NEW, BASE)), List.of())))
                .singleElement()
                .satisfies(refusal -> assertThat(refusal.index()).isZero());
    }

    @Test
    void shouldRefuseAnIntroducedBasisWhoseWitnessIsOnTheBaseSide() throws Exception {
        range(BASE, HEAD);
        assertThat(verifier.verify(check(
                        List.of(citation(0, CitationSide.OLD, BASE)),
                        List.of(new BasisToCheck(0, "introduced", 0, null)))))
                .singleElement()
                .satisfies(refusal -> assertThat(refusal.index()).isZero());
    }

    @Test
    void shouldRefuseAConflictingBaseVersion() throws Exception {
        range(BASE, HEAD);
        var original = check(List.of(citation(0, CitationSide.NEW, HEAD)), List.of());
        var conflict = new CitationCheck(
                original.workspaceId(),
                original.item(),
                original.version(),
                new ItemVersion("c".repeat(40), null),
                original.evidence(),
                original.citations(),
                original.bases());
        assertThat(verifier.verify(conflict))
                .singleElement()
                .satisfies(refusal -> assertThat(refusal.message()).contains("base version contradicts"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"c", "a"})
    void shouldRefuseATreeHeadBasisAtAnotherCommit(String prefix) throws Exception {
        range(BASE, HEAD);
        var history = new CitationToCheck(
                0, "scm.repository.tree", "src/a.ts", CitationSide.NEW, prefix.repeat(40), 1, 1, "words");
        assertThat(verifier.verify(check(List.of(history), List.of(new BasisToCheck(0, "introduced", 0, null)))))
                .singleElement()
                .satisfies(refusal -> assertThat(refusal.index()).isZero());
    }

    @Test
    void shouldVerifyATreeBasisWithExplicitHeadAndBaseRevisions() throws Exception {
        range(BASE, HEAD);
        var before =
                new CitationToCheck(0, "scm.repository.tree", "src/old.ts", CitationSide.NEW, BASE, 1, 1, "before");
        var after = new CitationToCheck(1, "scm.repository.tree", "src/new.ts", CitationSide.NEW, HEAD, 1, 1, "after");
        assertThat(verifier.verify(check(List.of(before, after), List.of(new BasisToCheck(0, "worsened", 1, 0)))))
                .isEmpty();
    }

    @Test
    void shouldRefuseASideOnATreeCitation() {
        var tree = new CitationToCheck(0, "scm.repository.tree", "src/a.ts", CitationSide.OLD, BASE, 1, 1, "words");
        assertThat(verifier.verify(check(List.of(tree), List.of()))).hasSize(1);
    }

    @Test
    void shouldUseTheSubmittedReviewItemRange() throws Exception {
        range(BASE, HEAD);
        var review = new ItemRef(1, 2, ScmItemEvents.PULL_REQUEST_REVIEW, 3L, 4L, null);
        var version = ScmVersion.pullRequestReview(
                BASE, HEAD, "Title", null, new ScmVersion.ReviewContent(HEAD, "Review", "COMMENTED"));
        assertThat(verifier.verify(new CitationCheck(
                        1,
                        review,
                        version,
                        new ItemVersion(BASE, null),
                        new EvidenceDirectory("inputs/", root),
                        List.of(citation(0, CitationSide.NEW, HEAD)),
                        List.of(new BasisToCheck(0, "introduced", 0, null)))))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.ts", "/outside.ts", "src/../a.ts", "src\\a.ts"})
    void shouldRefusePathsOutsideTheCapturedRepository(String path) {
        var citation = new CitationToCheck(0, "scm.pull-request.diff", path, CitationSide.NEW, HEAD, 1, 1, "words");
        assertThat(verifier.verify(check(List.of(citation), List.of()))).hasSize(1);
    }

    @Test
    void shouldVerifyCapturedCommitRecordsWithoutRepositorySideOrRevision() {
        var record = new CitationToCheck(
                0,
                "scm.pull-request.core",
                "context/commits.json#/commits/0/message",
                CitationSide.NEW,
                null,
                1,
                1,
                "Subject words");
        assertThat(verifier.verify(check(List.of(record), List.of()))).isEmpty();
        var old = new CitationToCheck(0, record.source(), record.path(), CitationSide.OLD, null, 1, 1, record.quote());
        assertThat(verifier.verify(check(List.of(old), List.of()))).hasSize(1);
        var revision =
                new CitationToCheck(0, record.source(), record.path(), CitationSide.NEW, HEAD, 1, 1, record.quote());
        assertThat(verifier.verify(check(List.of(revision), List.of()))).hasSize(1);
    }

    @Test
    void shouldRefuseAnIntroducedClaimOnUnchangedCodeInATouchedFile() throws Exception {
        range(BASE, HEAD);
        var unchanged =
                new CitationToCheck(0, "scm.pull-request.diff", "src/a.ts", CitationSide.NEW, HEAD, 2, 2, "unchanged");
        assertThat(verifier.verify(check(List.of(unchanged), List.of(new BasisToCheck(0, "introduced", 0, null)))))
                .singleElement()
                .satisfies(refusal -> {
                    assertThat(refusal.reason()).isEqualTo(CandidateRefusal.BASIS_WITNESS_MISSING);
                    assertThat(refusal.message()).contains("intersect added lines");
                });
    }

    @Test
    void shouldAcceptAWorsenedClaimWithAnUnchangedPinnedBaseWitness() throws Exception {
        range(BASE, HEAD);
        var unchanged =
                new CitationToCheck(0, "scm.repository.tree", "src/a.ts", CitationSide.NEW, BASE, 2, 2, "unchanged");
        assertThat(verifier.verify(check(
                        List.of(unchanged, citation(1, CitationSide.NEW, HEAD)),
                        List.of(new BasisToCheck(0, "worsened", 1, 0)))))
                .isEmpty();
    }

    @Test
    void shouldAcceptAdditiveWorseningAndRefuseDeletionOnlyAndPreexistingClaims() throws Exception {
        range(BASE, HEAD);
        var mapper = new ObjectMapper();
        var change = mapper.createObjectNode().put("base_sha", BASE).put("head_sha", HEAD);
        var base =
                new CitationToCheck(0, "scm.pull-request.diff", "src/a.ts", CitationSide.OLD, BASE, 1, 1, "unsafe();");
        var added =
                new CitationToCheck(1, "scm.pull-request.diff", "src/a.ts", CitationSide.NEW, HEAD, 2, 2, "unsafe();");
        var basis = List.of(new BasisToCheck(0, "worsened", 1, 0));
        change.put(
                "diff",
                "diff --git a/src/a.ts b/src/a.ts\n--- a/src/a.ts\n+++ b/src/a.ts\n@@ -1 +1,2 @@\n unsafe();\n+unsafe();\n");
        Files.writeString(root.resolve("context/change.json"), mapper.writeValueAsString(change));
        assertThat(verifier.verify(check(List.of(base, added), basis))).isEmpty();
        var preexisting =
                new CitationToCheck(1, added.source(), added.path(), CitationSide.NEW, HEAD, 1, 1, "unsafe();");
        assertThat(verifier.verify(check(List.of(base, preexisting), basis)))
                .singleElement()
                .satisfies(refusal -> assertThat(refusal.reason()).isEqualTo(CandidateRefusal.BASIS_WITNESS_MISSING));
        change.put(
                "diff",
                "diff --git a/src/a.ts b/src/a.ts\n--- a/src/a.ts\n+++ b/src/a.ts\n@@ -1,2 +1 @@\n unsafe();\n-unsafe();\n");
        Files.writeString(root.resolve("context/change.json"), mapper.writeValueAsString(change));
        assertThat(verifier.verify(check(List.of(base, preexisting), basis)))
                .singleElement()
                .satisfies(refusal -> assertThat(refusal.reason()).isEqualTo(CandidateRefusal.BASIS_WITNESS_MISSING));
        var historical =
                new CitationToCheck(0, base.source(), base.path(), CitationSide.OLD, "c".repeat(40), 1, 1, "unsafe();");
        assertThat(verifier.verify(check(List.of(historical, added), basis))).isNotEmpty();
    }

    @Test
    void shouldAcceptAWitnessRangeThatIntersectsAnAddedLine() throws Exception {
        range(BASE, HEAD);
        var spanning = new CitationToCheck(
                0, "scm.pull-request.diff", "src/a.ts", CitationSide.NEW, HEAD, 1, 2, "after\nunchanged");
        assertThat(verifier.verify(check(List.of(spanning), List.of(new BasisToCheck(0, "introduced", 0, null)))))
                .isEmpty();
    }

    @Test
    void shouldRefuseACodeBasisWhenThePinnedDiffIsMissing() throws Exception {
        range(BASE, HEAD);
        Files.writeString(
                root.resolve("context/change.json"), "{\"base_sha\":\"" + BASE + "\",\"head_sha\":\"" + HEAD + "\"}");
        assertThat(verifier.verify(check(
                        List.of(citation(0, CitationSide.NEW, HEAD)),
                        List.of(new BasisToCheck(0, "introduced", 0, null)))))
                .singleElement()
                .satisfies(refusal -> assertThat(refusal.reason()).isEqualTo(CandidateRefusal.BASIS_WITNESS_MISSING));
    }

    @Test
    void shouldRefuseACitationOfACheckRunThatHadNotFinishedAndKeepOneThatHad() throws Exception {
        Files.createDirectories(root.resolve("context"));
        Files.writeString(root.resolve("context/checks.json"), """
                {"head_check_runs": {"complete": true, "observed_at": "2026-10-27T05:00:00Z", "runs": [
                  {"name": "unit tests", "state": "in_progress", "finished": false},
                  {"name": "build", "state": "failure", "finished": true}]}}
                """);
        var running = new CitationToCheck(
                0,
                "scm.pull-request.checks",
                "context/checks.json#/head_check_runs/runs/0/state",
                CitationSide.NEW,
                null,
                1,
                1,
                "in_progress");
        var ended = new CitationToCheck(
                1,
                "scm.pull-request.checks",
                "context/checks.json#/head_check_runs/runs/1/state",
                CitationSide.NEW,
                null,
                1,
                1,
                "failure");

        assertThat(verifier.verify(check(List.of(running, ended), List.of())))
                .singleElement()
                .satisfies(refusal -> {
                    assertThat(refusal.index()).isZero();
                    assertThat(refusal.reason()).isEqualTo(CandidateRefusal.UNFINISHED_RUN_CITED);
                });
    }

    @Test
    void shouldRefuseLinesOfTheWholeRecordThatShowACheckRunThatHadNotFinished() throws Exception {
        Files.createDirectories(root.resolve("context"));
        var mapper = new ObjectMapper();
        var record = mapper.readTree("""
                {"merge_state_status": "CLEAN", "head_check_runs": {"complete": false, "observed_at": "2026-10-27T05:00:00Z",
                  "runs": [{"name": "unit tests", "state": "in_progress", "finished": false},
                           {"name": "build", "state": "failure", "finished": true}]}}
                """);
        // Pretty-printed, as the capture writes the record.
        String text = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record);
        Files.writeString(root.resolve("context/checks.json"), text);
        List<String> lines = text.lines().toList();
        int running = lineOf(lines, "\"in_progress\"");
        int failed = lineOf(lines, "\"failure\"");
        int statusLine = lineOf(lines, "\"CLEAN\"");
        assertThat(List.of(running, failed, statusLine)).doesNotContain(0);

        assertThat(verifier.verify(check(
                        List.of(
                                wholeRecord(0, running, "in_progress"),
                                wholeRecord(1, failed, "failure"),
                                wholeRecord(2, statusLine, "CLEAN")),
                        List.of())))
                .singleElement()
                .satisfies(refusal -> {
                    assertThat(refusal.index()).isZero();
                    assertThat(refusal.reason()).isEqualTo(CandidateRefusal.UNFINISHED_RUN_CITED);
                });
    }

    private static int lineOf(List<String> lines, String text) {
        for (int index = 0; index < lines.size(); index++) if (lines.get(index).contains(text)) return index + 1;
        return 0;
    }

    private static CitationToCheck wholeRecord(int index, int line, String quote) {
        return new CitationToCheck(
                index, "scm.pull-request.checks", "context/checks.json", CitationSide.NEW, null, line, line, quote);
    }

    private void range(String base, String head) throws Exception {
        Files.createDirectories(root.resolve("context"));
        var mapper = new ObjectMapper();
        var change = mapper.createObjectNode().put("base_sha", base).put("head_sha", head);
        change.put("diff", """
                diff --git a/src/a.ts b/src/a.ts
                --- a/src/a.ts
                +++ b/src/a.ts
                @@ -1,2 +1,2 @@
                -before
                +after
                 unchanged
                diff --git a/src/old.ts b/src/new.ts
                --- a/src/old.ts
                +++ b/src/new.ts
                @@ -1 +1 @@
                -before
                +after
                """);
        Files.writeString(root.resolve("context/change.json"), mapper.writeValueAsString(change));
        for (String side : List.of("old", "new")) {
            Files.createDirectories(root.resolve("context/citable/" + side + "/src"));
            Files.writeString(root.resolve("context/citable/" + side + "/src/a.ts"), "words");
        }
    }

    private CitationCheck check(List<CitationToCheck> citations, List<BasisToCheck> bases) {
        return new CitationCheck(
                1,
                new ItemRef(1, 2, ScmItemEvents.PULL_REQUEST, 3L, null, null),
                ScmVersion.pullRequest(BASE, HEAD, "Title", null),
                new ItemVersion(BASE, null),
                new EvidenceDirectory("inputs/", root),
                citations,
                bases);
    }

    private static CitationToCheck citation(int index, CitationSide side, @Nullable String revision) {
        return new CitationToCheck(index, "scm.pull-request.diff", "src/a.ts", side, revision, 1, 1, "words");
    }
}
