package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The work-place scope keeps only what the public results need, and nothing private changes it: not the private
 * brief, not the developer's problem history, not a private or withheld candidate's text, practice entry or criteria.
 */
@Tag("unit")
class WorkPlaceScopeTest {
    private static final JsonMapper MAPPER = new JsonMapper();
    private static final List<String> FORMS = List.of("FILE_NOTE", "LINE_NOTE");
    private static final String PRIVATE_TEXT = "context/staged/" + "f".repeat(64) + ".txt";

    @Test
    void shouldKeepNothingPrivateOnAnyFixture() throws Exception {
        var fixtures = new ArrayList<WorkPlaceScope.Sealed>();
        for (var scenario : CompositionEvalCases.load())
            fixtures.add(WorkPlaceShadowComparisonTest.sealed(scenario.inputs(), scenario.response(), FORMS));
        fixtures.add(pullRequest());
        for (var sealed : fixtures) {
            var scoped = WorkPlaceScope.of(sealed).orElseThrow();
            var files = scoped.payload().path("files");
            assertThat(files.propertyNames())
                    .noneMatch(path -> path.equals("INDEX.md")
                            || path.startsWith("inputs/history/")
                            || path.startsWith("composition/")
                            || path.equals(SandboxLayout.FEEDBACK_COMPOSITION_PATH));
            assertThat(scoped.payload().path("results"))
                    .allSatisfy(
                            result -> assertThat(result.path("publicEligible").asBoolean())
                                    .isTrue());
        }
    }

    @Test
    void shouldHandTheSameCopyWhateverAPrivateOrWithheldCandidateAdds() {
        var plain = WorkPlaceScope.of(pullRequest()).orElseThrow();
        var basis = new TreeMap<>(pullRequest().basis());
        // The private brief and the developer's problem history differ.
        basis.put("INDEX.md", "A different brief".getBytes(StandardCharsets.UTF_8));
        basis.put("inputs/history/problems.json", "[{\"problemId\":\"x\"}]".getBytes(StandardCharsets.UTF_8));
        // A private candidate staged its own text, its practice's entry and its criteria.
        byte[] text = "private evidence".getBytes(StandardCharsets.UTF_8);
        basis.put(PRIVATE_TEXT, text);
        var index = (ObjectNode) MAPPER.readTree(basis.get(SandboxLayout.MANIFEST_PATH));
        ((ArrayNode) index.path("citableTexts"))
                .addObject()
                .put("source", "scm.repository.tree")
                .put("path", "src/Private.java")
                .put("side", "NEW")
                .put("commit", "2222222222222222222222222222222222222222")
                .put("stagedPath", PRIVATE_TEXT)
                .put("sha256", ProvenanceDigest.sha256Hex(text));
        basis.put(SandboxLayout.MANIFEST_PATH, MAPPER.writeValueAsBytes(index));
        var practices = (ArrayNode) MAPPER.readTree(basis.get(ComposerFiles.PRACTICE_INDEX));
        practices.addObject().put("slug", "reviews-privately").put("name", "Reviews privately, staged");
        basis.put(ComposerFiles.PRACTICE_INDEX, MAPPER.writeValueAsBytes(practices));
        basis.put(ComposerFiles.criteria("reviews-privately"), "private criteria".getBytes(StandardCharsets.UTF_8));
        // And the admission holds a private result of that practice, citing that text.
        var results = new ArrayList<>(pullRequest().results());
        var hidden = (ObjectNode) results.getFirst().deepCopy();
        hidden.put("resultId", "30000000-0000-4000-8000-00000000000f");
        hidden.put("practiceSlug", "reviews-privately");
        hidden.put("publicEligible", false);
        ((ObjectNode) hidden.path("evidence").path("citations").get(0))
                .put("sourceKind", "scm.repository.tree")
                .put("path", "src/Private.java")
                .putObject("verification")
                .put("itemSha256", ProvenanceDigest.sha256Hex(text));
        results.add(hidden);
        var base = pullRequest();
        var assigned = new ArrayList<>(base.assignedPractices());
        assigned.add("reviews-privately");
        var withPrivate = WorkPlaceScope.of(new WorkPlaceScope.Sealed(
                        basis,
                        results,
                        base.capturedSources(),
                        assigned,
                        base.onWorkCopyForms(),
                        base.root(),
                        base.stagedCopies(),
                        base.stagedExpiresAt(),
                        base.sealedAt(),
                        base.occasionExpiresAt()))
                .orElseThrow();
        var plainAssigned = WorkPlaceScope.of(new WorkPlaceScope.Sealed(
                        base.basis(),
                        base.results(),
                        base.capturedSources(),
                        assigned,
                        base.onWorkCopyForms(),
                        base.root(),
                        base.stagedCopies(),
                        base.stagedExpiresAt(),
                        base.sealedAt(),
                        base.occasionExpiresAt()))
                .orElseThrow();

        assertThat(withPrivate.payload()).isEqualTo(plainAssigned.payload());
        assertThat(withPrivate.digest()).isEqualTo(plainAssigned.digest());
        assertThat(withPrivate.payload().path("files").has(PRIVATE_TEXT)).isFalse();
        assertThat(withPrivate.payload().path("notReached").toString()).contains("reviews-privately");
        assertThat(plain.payload().path("files"))
                .isEqualTo(plainAssigned.payload().path("files"));
    }

    @Test
    void shouldRefuseACopyWhoseBytesOrResultsChanged() {
        var scoped = WorkPlaceScope.of(pullRequest()).orElseThrow();
        assertThat(WorkPlaceScope.read(scoped.payload(), scoped.digest()).results())
                .isNotEmpty();

        var file = scoped.payload().deepCopy();
        var files = (ObjectNode) file.path("files");
        String path = files.propertyNames().stream()
                .filter(name -> name.startsWith("context/"))
                .findFirst()
                .orElseThrow();
        files.put(path, Base64.getEncoder().encodeToString("changed".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> WorkPlaceScope.read(file, scoped.digest()))
                .isInstanceOf(IllegalArgumentException.class);

        var result = scoped.payload().deepCopy();
        ((ObjectNode) result.path("results").get(0)).put("summary", "A changed summary");
        assertThatThrownBy(() -> WorkPlaceScope.read(result, scoped.digest()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseACopyWhoseEligibilityFlagsChanged() {
        var scoped = WorkPlaceScope.of(pullRequest()).orElseThrow();
        assertThat(WorkPlaceScope.read(scoped.payload(), scoped.digest()).results())
                .isNotEmpty()
                .allSatisfy(result -> {
                    assertThat(result.path("publicEligible").asBoolean()).isTrue();
                    result.path("problems")
                            .forEach(problem -> assertThat(
                                            problem.path("publicEligible").asBoolean())
                                    .isTrue());
                });

        var hidden = scoped.payload().deepCopy();
        ((ObjectNode) hidden.path("results").get(0)).put("publicEligible", false);
        assertThatThrownBy(() -> WorkPlaceScope.read(hidden, scoped.digest()))
                .as("a flag that would silence a public finding changes the digest")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReadTheCopyAfterTheStoreReordersItsKeys() {
        var scoped = WorkPlaceScope.of(pullRequest()).orElseThrow();
        var reordered = reversed(scoped.payload());
        assertThat(reordered.toString()).isNotEqualTo(scoped.payload().toString());

        assertThat(WorkPlaceScope.read(reordered, scoped.digest()).results())
                .isEqualTo(
                        WorkPlaceScope.read(scoped.payload(), scoped.digest()).results());
    }

    @Test
    void shouldExpireAtTheCopyDeadlineOrWithTheInputsItKeeps() {
        var base = pullRequest();
        assertThat(WorkPlaceScope.of(base).orElseThrow().expiresAt())
                .isEqualTo(base.sealedAt().plus(WorkPlaceScope.COPY_DEADLINE));
        Instant sooner = base.sealedAt().plus(Duration.ofMinutes(10));
        String kept = base.results().getFirst().path("practiceSlug").asString();

        assertThat(WorkPlaceScope.of(expiring(base, Map.of(), sooner))
                        .orElseThrow()
                        .expiresAt())
                .as("the occasion's evidence ends")
                .isEqualTo(sooner);
        assertThat(WorkPlaceScope.of(expiring(base, Map.of(kept, sooner), null))
                        .orElseThrow()
                        .expiresAt())
                .as("a kept result's staged evidence ends")
                .isEqualTo(sooner);
        assertThat(WorkPlaceScope.of(expiring(base, Map.of("reviews-privately", sooner), null))
                        .orElseThrow()
                        .expiresAt())
                .as("a private candidate's staged evidence never shortens the copy")
                .isEqualTo(base.sealedAt().plus(WorkPlaceScope.COPY_DEADLINE));
    }

    private static WorkPlaceScope.Sealed expiring(
            WorkPlaceScope.Sealed base, Map<String, Instant> staged, @Nullable Instant occasion) {
        return new WorkPlaceScope.Sealed(
                base.basis(),
                base.results(),
                base.capturedSources(),
                base.assignedPractices(),
                base.onWorkCopyForms(),
                base.root(),
                base.stagedCopies(),
                staged,
                base.sealedAt(),
                occasion);
    }

    /** The node with every object's keys in reverse order, as a store that orders keys its own way may return it. */
    private static JsonNode reversed(JsonNode node) {
        if (node.isObject()) {
            var names = new ArrayList<>(node.propertyNames());
            Collections.reverse(names);
            var copy = MAPPER.createObjectNode();
            names.forEach(name -> copy.set(name, reversed(node.path(name))));
            return copy;
        }
        if (node.isArray()) {
            var copy = MAPPER.createArrayNode();
            node.forEach(element -> copy.add(reversed(element)));
            return copy;
        }
        return node;
    }

    @Test
    void shouldHandNothingWhenNoPublicResultIsNotMet() {
        var base = pullRequest();
        var results = new ArrayList<JsonNode>();
        for (JsonNode result : base.results()) {
            var copy = (ObjectNode) result.deepCopy();
            copy.put("publicEligible", false);
            results.add(copy);
        }
        assertThat(WorkPlaceScope.of(new WorkPlaceScope.Sealed(
                        base.basis(),
                        results,
                        base.capturedSources(),
                        base.assignedPractices(),
                        base.onWorkCopyForms(),
                        base.root(),
                        base.stagedCopies(),
                        base.stagedExpiresAt(),
                        base.sealedAt(),
                        base.occasionExpiresAt())))
                .isEmpty();
    }

    /** The golden pull request: its retained inputs and its admission answer. */
    private static WorkPlaceScope.Sealed pullRequest() {
        try {
            var basis = ComposerReplay.basis(MAPPER.readTree("{\"scenario\": \"pull-request\"}"));
            Map<String, byte[]> inputs = basis.inputs();
            return WorkPlaceShadowComparisonTest.sealed(inputs, basis.admission(), FORMS);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
