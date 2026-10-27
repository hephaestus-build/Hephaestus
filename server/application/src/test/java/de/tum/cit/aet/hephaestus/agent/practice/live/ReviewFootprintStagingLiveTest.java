package de.tum.cit.aet.hephaestus.agent.practice.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.tum.cit.aet.hephaestus.agent.practice.PracticeSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stages the workspace of one practice review for the sandbox footprint benchmark ({@code scripts/sandbox-footprint.ts}):
 * a real change of a real repository, every bundled practice for pull requests, and the runner's files and environment
 * as the adapter builds them. The benchmark serves the directory through its stand-in gateway to the agent image, so
 * only the model is a stand-in.
 *
 * <p>Runs only when {@code FOOTPRINT_STAGE_DIR} names an empty or absent directory. {@code FOOTPRINT_REPO} is a local
 * clone; {@code FOOTPRINT_BASE} and {@code FOOTPRINT_HEAD} name the change; {@code FOOTPRINT_PRACTICES} caps the number
 * of practices (all by default).
 */
@Tag("live")
class ReviewFootprintStagingLiveTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String KIND = "scm.pull_request";

    @Test
    void shouldStageAReviewOfTheBundledPracticesOnARealChange() throws Exception {
        String target = System.getenv("FOOTPRINT_STAGE_DIR");
        assumeTrue(target != null && !target.isBlank(), "FOOTPRINT_STAGE_DIR is not set");
        Path workspace = Path.of(target).toAbsolutePath();
        Path source = Path.of(required("FOOTPRINT_REPO")).toAbsolutePath();
        String base = required("FOOTPRINT_BASE");
        String head = required("FOOTPRINT_HEAD");
        int cap = LiveProperty.positiveInt("FOOTPRINT_PRACTICES", System.getenv("FOOTPRINT_PRACTICES"))
                .orElse(Integer.MAX_VALUE);
        Files.createDirectories(workspace);
        try (var entries = Files.list(workspace)) {
            assertThat(entries).as("an empty stage directory").isEmpty();
        }

        PracticeSandboxSpec spec = LiveRunnerWorkspace.adapter()
                .buildSandboxSpec(LiveRunnerWorkspace.request(
                        "footprint-model", "footprint-token", Map.of(), LiveModelSettings.DEFAULTS));
        for (var file : spec.inputFiles().entrySet()) {
            Path path = workspace.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.write(path, file.getValue());
        }

        Path repo = workspace.resolve(SandboxLayout.REPO_MOUNT_RELATIVE);
        Files.createDirectories(repo.getParent());
        PracticeCriteriaCaseFixtures.fixtureCommand(
                workspace, "git", "clone", "--quiet", "--no-local", source.toString(), repo.toString());
        PracticeCriteriaCaseFixtures.fixtureCommand(
                workspace, "git", "-C", repo.toString(), "checkout", "-q", "--detach", head);
        Files.writeString(repo.resolve(".git/" + GitRepositoryManager.CAPTURED_REFS), "");
        String baseSha = PracticeCriteriaCaseFixtures.fixtureCommand(
                        workspace, "git", "-C", repo.toString(), "rev-parse", base)
                .strip();
        String headSha = PracticeCriteriaCaseFixtures.fixtureCommand(
                        workspace, "git", "-C", repo.toString(), "rev-parse", "HEAD")
                .strip();
        int additions = PracticeCriteriaCaseFixtures.fixtureCommand(
                        workspace, "git", "-C", repo.toString(), "diff", "--numstat", baseSha, headSha)
                .lines()
                .mapToInt(line -> line.startsWith("-") ? 0 : Integer.parseInt(line.substring(0, line.indexOf('\t'))))
                .sum();
        String title = PracticeCriteriaCaseFixtures.fixtureCommand(
                        workspace, "git", "-C", repo.toString(), "log", "-1", "--format=%s", headSha)
                .strip();

        Path context = workspace.resolve(SandboxLayout.CONTEXT_PREFIX);
        Files.createDirectories(context.resolve("docs"));
        Files.writeString(context.resolve("description.md"), "");
        Files.write(
                context.resolve("change.json"),
                MAPPER.writeValueAsBytes(Map.of("base_sha", baseSha, "head_sha", headSha)));
        ObjectNode record = LiveWorkRecords.pullRequest(headSha, title, "", additions);
        Files.write(context.resolve("metadata.json"), LiveWorkRecords.staged(record));
        Files.copy(PracticeCriteriaCaseFixtures.FIXTURE_DIR.resolve("comments.json"), context.resolve("comments.json"));
        Files.write(
                workspace.resolve(SandboxLayout.MANIFEST_PATH),
                MAPPER.writeValueAsBytes(LiveRunnerWorkspace.manifest(workspace, LiveRunnerWorkspace.capturedFiles())));

        var catalog = PracticeCriteriaCaseFixtures.catalog();
        Path practices = workspace.resolve(SandboxLayout.PRACTICES_PREFIX);
        Files.createDirectories(practices);
        var index = new ArrayList<ObjectNode>();
        Map<String, JsonNode> definitions = new LinkedHashMap<>();
        long versionId = 0;
        for (var entry : catalog.practices()) {
            if (!entry.definition().kind().value().equals(KIND) || index.size() >= cap) {
                continue;
            }
            var definition = entry.definition().definition();
            var row = MAPPER.createObjectNode()
                    .put("slug", entry.slug())
                    .put("name", definition.name())
                    .put("versionId", Long.toString(++versionId));
            var exhaustive = row.putArray("exhaustiveSources");
            var limits = row.putArray("knownLimitations");
            definition.reviewedBy().limits().forEach(limit -> limits.add(limit.description()));
            var reads = row.putArray("readsSources");
            definition.reads().sources().forEach(read -> {
                reads.add(read.source().value());
                if (read.need() == ReadNeed.COMPLETE) {
                    exhaustive.add(read.source().value());
                }
            });
            index.add(row);
            Files.writeString(practices.resolve(entry.slug() + ".md"), definition.criteria());
            definitions.put(entry.slug(), MAPPER.valueToTree(definition));
        }
        Files.write(practices.resolve("index.json"), MAPPER.writeValueAsBytes(index));
        LiveRunnerWorkspace.stageTask(
                workspace,
                "Review merge request #1 in footprint/benchmark against "
                        + String.join(", ", definitions.keySet()) + ". Follow " + SandboxLayout.ORCHESTRATOR_PATH
                        + ".",
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                1L);
        LiveRunnerWorkspace.stageFixtureInputs(workspace, KIND, definitions);

        Files.write(
                workspace.resolveSibling(workspace.getFileName() + ".spec.json"),
                MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(Map.of(
                                "command", spec.command(),
                                "environment", spec.environment(),
                                "practices", List.copyOf(definitions.keySet()))));
        assertThat(definitions).isNotEmpty();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertThat(value).as(name).isNotBlank();
        return value;
    }
}
