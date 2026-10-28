package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.ReviewLimit;
import de.tum.cit.aet.hephaestus.practices.ReviewedBy;
import de.tum.cit.aet.hephaestus.practices.ScanDefinition;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PracticeCatalogInjectorTest extends BaseUnitTest {
    @Test
    void shouldStageOnlyAssignedPinnedDefinitionsAndScripts() throws IOException {
        var mapper = new JsonMapper();
        var files = new LinkedHashMap<String, byte[]>();
        var pinned = RuntimeReviewTestData.assignment(5L, "export default {};");
        new PracticeCatalogInjector(mapper).inject(files, List.of(pinned), ReviewKind.ITEM, Map.of());
        assertThat(files)
                .containsOnlyKeys(
                        SandboxLayout.PRACTICES_PREFIX + "index.json",
                        SandboxLayout.PRACTICES_PREFIX + "clear-names.md",
                        SandboxLayout.PRACTICE_SCRIPT_PRACTICES_PREFIX + "clear-names.ts");
        assertThat(new String(files.get(SandboxLayout.PRACTICES_PREFIX + "clear-names.md"), StandardCharsets.UTF_8))
                .isEqualTo("Use clear names.");
        var index = mapper.readTree(files.get(SandboxLayout.PRACTICES_PREFIX + "index.json"));
        var fixture = mapper.readTree(Files.readString(Path.of("src/test/resources/agent/practice-index.json")));
        assertThat(index).isEqualTo(fixture);
    }

    @Test
    void shouldStageNoAuthoredScriptForTheBuiltInProgram() {
        var files = new LinkedHashMap<String, byte[]>();
        new PracticeCatalogInjector(new JsonMapper())
                .inject(files, List.of(RuntimeReviewTestData.assignment(null, null)), ReviewKind.ITEM, Map.of());
        assertThat(files).doesNotContainKey(SandboxLayout.PRACTICE_SCRIPT_PRACTICES_PREFIX + "clear-names.ts");
    }

    @Test
    void scanIndexCarriesOnlyTheFrozenGrantAndTheScanReadRequirements() {
        var mapper = new JsonMapper();
        var pinned = scanAssignment();
        for (var paths : List.of(List.<String>of(), List.of("docs/rules.md"))) {
            var files = new LinkedHashMap<String, byte[]>();
            new PracticeCatalogInjector(mapper)
                    .inject(files, List.of(pinned), ReviewKind.SCAN, Map.of(pinned.id(), paths));
            var entry = mapper.readTree(files.get(SandboxLayout.PRACTICES_PREFIX + "index.json"))
                    .get(0);
            assertThat(entry.path("contextPaths")).isEqualTo(mapper.valueToTree(paths));
            assertThat(entry.path("readsSources")).isEqualTo(mapper.valueToTree(List.of("docs.document.core")));
            assertThat(entry.path("exhaustiveSources")).isEqualTo(entry.path("readsSources"));
        }
    }

    @Test
    void shouldStageAuthoredReviewLimits() {
        var original = RuntimeReviewTestData.assignment(null, null);
        var p = original.practice();
        var d = p.definition();
        var limit = new ReviewLimit("context", "Cannot judge behavior outside the captured document.");
        var definition = new PracticeDefinition(
                d.name(),
                d.reviewOn(),
                d.appliesWhen(),
                d.about(),
                d.reads(),
                new ReviewedBy(d.reviewedBy().by(), d.reviewedBy().humanReason(), List.of(limit)),
                d.criteria(),
                d.script(),
                d.scan(),
                d.lineNotes(),
                d.guidance());
        var assignment = new ReviewAssignmentSnapshot(
                original.id(),
                original.item(),
                original.version(),
                original.developerId(),
                original.practiceSlug(),
                new PinnedPractice(p.practiceId(), p.practiceVersionId(), definition, p.reviewDigest(), p.method()));
        var mapper = JsonMapper.builder().build();
        var files = new LinkedHashMap<String, byte[]>();
        new PracticeCatalogInjector(mapper).inject(files, List.of(assignment), ReviewKind.ITEM, Map.of());
        assertThat(mapper.readTree(files.get(SandboxLayout.PRACTICES_PREFIX + "index.json"))
                        .get(0)
                        .path("knownLimitations"))
                .isEqualTo(mapper.valueToTree(List.of(limit.description())));
    }

    @Test
    void missingScanGrantIsRefusedRatherThanExposingAllCapturedPaths() {
        assertThatThrownBy(() -> new PracticeCatalogInjector(new JsonMapper())
                        .inject(new LinkedHashMap<>(), List.of(scanAssignment()), ReviewKind.SCAN, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Missing frozen scan context grant");
    }

    static ReviewAssignmentSnapshot scanAssignment() {
        var original = RuntimeReviewTestData.assignment(null, null);
        var p = original.practice();
        var d = p.definition();
        var scan = new ScanDefinition(
                original.item().kind(),
                List.of("**"),
                List.of(),
                List.of("docs/*.md"),
                new PracticeReads(
                        "3.0.0",
                        List.of(new PracticeReadSource(new SourceKind("docs.document.core"), ReadNeed.COMPLETE))));
        var definition = new PracticeDefinition(
                d.name(),
                d.reviewOn(),
                d.appliesWhen(),
                d.about(),
                d.reads(),
                d.reviewedBy(),
                d.criteria(),
                d.script(),
                scan,
                d.lineNotes(),
                d.guidance());
        return new ReviewAssignmentSnapshot(
                original.id(),
                original.item(),
                original.version(),
                original.developerId(),
                original.practiceSlug(),
                new PinnedPractice(p.practiceId(), p.practiceVersionId(), definition, p.reviewDigest(), p.method()));
    }
}
