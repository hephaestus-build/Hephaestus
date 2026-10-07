package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Enforces the {@link ContentSource} contract (ADR: integration content = EXTRACT+LOAD of raw native
 * objects only; no practice-dependent Transform in a connector). A connector that names a practice or
 * pre-renders a observation is, by definition, code in the wrong layer — this test makes that structural.
 *
 * <p>Scope: the SCM-side sources under {@code agent.context.providers} (the mentor content sources under
 * {@code providers.mentor} are core-native projections, out of scope). A connector that computes
 * practice-shaped features from the mounted worktree (as {@code TestPresenceContentSource} /
 * {@code BranchGraphContentSource} would) is the Transform-in-the-wrong-layer this test forbids.
 */
class ContentSourceContractTest extends BaseUnitTest {

    private static final Path PROVIDERS_DIR = resolveDir(
            "src/main/java/de/tum/cit/aet/hephaestus/agent/context/providers",
            "server/application/src/main/java/de/tum/cit/aet/hephaestus/agent/context/providers");

    @Test
    @DisplayName("no SCM content provider names a practice slug — connectors carry no practice-dependent logic")
    void noProviderNamesAPractice() throws IOException {
        Set<String> slugs = practiceSlugs();
        assertThat(slugs).as("catalogue slugs should load").isNotEmpty();

        for (Path provider : scmProviderSources()) {
            String src = Files.readString(provider, StandardCharsets.UTF_8);
            for (String slug : slugs) {
                assertThat(src)
                        .as(
                                "%s names the practice '%s' — a connector must not encode practice-dependent logic "
                                        + "(move it to the precompute script or the agent)",
                                provider.getFileName(), slug)
                        .doesNotContain(slug);
            }
        }
    }

    @Test
    @DisplayName("the deleted worktree-derived feature providers must not return")
    void deletedFeatureProvidersStayDeleted() {
        for (String banned :
                List.of("TestPresenceContentSource", "BranchGraphContentSource", "AcceptanceCriteriaContentSource")) {
            assertThat(PROVIDERS_DIR.resolve(banned + ".java"))
                    .as("%s was deleted as worktree-derived Transform (or never built) — it must not reappear", banned)
                    .doesNotExist();
        }
    }

    @Test
    @DisplayName("no SCM provider re-introduces a practice-shaped derived aggregate (supersession is the agent's job)")
    void noProviderEmitsADerivedAggregate() throws IOException {
        // A supersession gate (changesRequestedUnaddressed) is a lossy derived aggregate: an EXTRACT+LOAD
        // connector must emit raw rows + submittedAt and let the agent compute supersession itself, never
        // pre-compute the gate. Guards the SCM providers against that aggregate creeping in.
        for (Path provider : scmProviderSources()) {
            String src = Files.readString(provider, StandardCharsets.UTF_8);
            for (String bannedAggregate : List.of("changesRequestedUnaddressed", "countUnaddressedChangesRequested")) {
                assertThat(src)
                        .as(
                                "%s re-introduces the derived '%s' aggregate — emit raw rows, let the agent judge",
                                provider.getFileName(), bannedAggregate)
                        .doesNotContain(bannedAggregate);
            }
        }
    }

    private static List<Path> scmProviderSources() throws IOException {
        try (Stream<Path> walk = Files.list(PROVIDERS_DIR)) {
            return walk.filter(p -> p.getFileName().toString().endsWith("ContentSource.java"))
                    .toList();
        }
    }

    @Test
    @DisplayName("catalogue slugs are the practices', never their groups'")
    void shouldReadPracticeSlugsButNotGroupSlugsWhenParsingTheCatalogue() {
        JsonNode catalogue = JSON.readTree("""
                {"groups": [{"slug": "communication", "practices": [{"slug": "ready-and-traceable-handoff"}]}]}
                """);

        assertThat(practiceSlugs(catalogue)).containsExactly("ready-and-traceable-handoff");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Set<String> practiceSlugs() throws IOException {
        try (InputStream in = ContentSourceContractTest.class
                .getClassLoader()
                .getResourceAsStream("practices/default-catalog.json")) {
            return practiceSlugs(JSON.readTree(in));
        }
    }

    private static Set<String> practiceSlugs(JsonNode catalogue) {
        Set<String> slugs = new TreeSet<>();
        for (JsonNode group : catalogue.path("groups")) {
            for (JsonNode practice : group.path("practices")) {
                JsonNode slug = practice.get("slug");
                if (slug != null && slug.isString()) {
                    slugs.add(slug.asString());
                }
            }
        }
        return slugs;
    }

    private static Path resolveDir(String moduleRelative, String repoRelative) {
        Path candidate = Path.of(moduleRelative);
        return Files.isDirectory(candidate) ? candidate : Path.of(repoRelative);
    }
}
