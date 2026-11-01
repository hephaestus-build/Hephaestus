package de.tum.cit.aet.hephaestus.agent.practice.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
class PracticeCriteriaCaseStagingIntegrationTest {

    @TempDir
    Path root;

    @ParameterizedTest(name = CriteriaCaseSelection.NAME)
    @MethodSource(CriteriaCaseSelection.SOURCE)
    void shouldStageCompleteSourcesAndShippedCriteriaWithoutReferenceAnswers(JsonNode scenario) throws Exception {
        var mapper = JsonMapper.builder().build();
        Path workspace = root.resolve(scenario.path("id").asString());
        Files.createDirectories(workspace);
        for (String sidecar : List.of("pi-change.ts", "pi-task-paths.ts")) {
            Files.copy(Path.of("src/main/resources/agent", sidecar), workspace.resolve(sidecar));
        }
        var definitions = PracticeCriteriaCaseFixtures.stage(workspace, scenario);
        LiveRunnerWorkspace.stageFixtureInputs(
                workspace,
                scenario.path("workType").asString().equals("issue") ? "scm.issue" : "scm.pull_request",
                definitions);
        for (var entry : definitions.entrySet()) {
            Path script = workspace.resolve(SandboxLayout.PRACTICE_SCRIPT_PRACTICES_PREFIX + entry.getKey() + ".ts");
            var program = entry.getValue().path("script");
            if (program.isNull()) {
                assertThat(script).doesNotExist();
            } else {
                assertThat(script).as("bundled program for %s", entry.getKey()).hasContent(program.asString());
            }
        }
        var practiceIndex = mapper.readTree(
                workspace.resolve(SandboxLayout.PRACTICES_PREFIX + "index.json").toFile());
        assertThat(practiceIndex)
                .extracting(entry -> entry.path("slug").asString())
                .containsExactlyInAnyOrderElementsOf(scenario.path("expected").propertyNames());
        if (scenario.path("expected").has("handles-errors-instead-of-swallowing-them")) {
            var entry = StreamSupport.stream(practiceIndex.spliterator(), false)
                    .filter(candidate ->
                            candidate.path("slug").asString().equals("handles-errors-instead-of-swallowing-them"))
                    .findFirst()
                    .orElseThrow();
            assertThat(entry.path("exhaustiveSources"))
                    .extracting(JsonNode::asString)
                    .containsExactly("scm.pull-request.diff");
            assertThat(entry.path("readsSources"))
                    .extracting(JsonNode::asString)
                    .containsExactly("scm.pull-request.diff", "scm.repository.tree");
        }
        var index =
                mapper.readTree(workspace.resolve(SandboxLayout.MANIFEST_PATH).toFile());
        var paths = new HashSet<String>();
        for (var artifact : index.path("files")) {
            String path = artifact.path("file").path("path").asString();
            assertThat(paths.add(path))
                    .as("unique artifact in %s", scenario.path("id"))
                    .isTrue();
            assertThat(workspace.resolve(path)).exists();
        }
        assertThat(index.path("sources"))
                .noneMatch(source -> source.path("kind").asString().equals("scm.pull-request.commits"));
        String commentsKind = scenario.path("workType").asString().equals("issue")
                ? "scm.issue.comments"
                : "scm.pull-request.comments";
        for (var artifact : index.path("files")) {
            String path = artifact.path("file").path("path").asString();
            if (path.equals(PracticeCriteriaCaseFixtures.COMMITS)) {
                assertThat(artifact.path("kind").asString()).isEqualTo("scm.pull-request.core");
            }
            if (path.equals(PracticeCriteriaCaseFixtures.COMMENTS)) {
                assertThat(artifact.path("kind").asString())
                        .as("comments source in %s", scenario.path("id"))
                        .isEqualTo(commentsKind);
            }
            // What the case expects, and why, is the oracle: no staged input may carry it.
            assertThat(Files.readString(workspace.resolve(path)))
                    .as("staged %s in %s", path, scenario.path("id"))
                    .doesNotContain(scenario.path("reason").asString());
        }
        String comments = Files.readString(workspace.resolve(PracticeCriteriaCaseFixtures.COMMENTS))
                .strip();
        assertThat(index.path("sources"))
                .anySatisfy(source -> {
                    assertThat(source.path("kind").asString()).isEqualTo(commentsKind);
                    assertThat(source.path("state").path("availability").asString())
                            .isEqualTo("AVAILABLE");
                    assertThat(source.path("state").path("content").asString())
                            .isEqualTo(comments.equals("[]") ? "EMPTY" : "NON_EMPTY");
                })
                .noneMatch(source -> source.path("kind")
                        .asString()
                        .equals(
                                commentsKind.equals("scm.issue.comments")
                                        ? "scm.pull-request.comments"
                                        : "scm.issue.comments"));
        var task = mapper.readTree(
                workspace.resolve(SandboxLayout.TASK_ENVELOPE_FILENAME).toFile());
        assertThat(task.path("task").path("prompt").asString())
                .doesNotContain(scenario.path("reason").asString());
        var files = mapper.readTree(workspace.resolve("work/change/files.json").toFile())
                .path("files");
        // The change is the difference between the base files and the files: added, changed and removed paths.
        var supplied = scenario.path("files");
        var baseFiles = scenario.path("baseFiles");
        var changed = new HashSet<String>();
        for (var entry : supplied.properties()) {
            if (entry.getKey().startsWith("repo/") && !entry.getValue().equals(baseFiles.path(entry.getKey()))) {
                changed.add(entry.getKey().substring("repo/".length()));
            }
        }
        for (var entry : baseFiles.properties()) {
            if (!supplied.has(entry.getKey())) {
                changed.add(entry.getKey().substring("repo/".length()));
            }
        }
        var statuses = new HashMap<String, String>();
        // The change detects renames: one entry under the new path names the removed path.
        var renamedTo = new HashMap<String, String>();
        for (var file : files) {
            statuses.put(file.path("path").asString(), file.path("status").asString());
            if (file.has("oldPath")) {
                renamedTo.put(file.path("oldPath").asString(), file.path("path").asString());
            }
        }
        var changedPaths = new HashSet<>(statuses.keySet());
        changedPaths.addAll(renamedTo.keySet());
        assertThat(changedPaths).as("changed files in %s", scenario.path("id")).isEqualTo(changed);
        // The supplied before-state is committed; a base file the case leaves out is deleted at head.
        var change = mapper.readTree(workspace.resolve("context/change.json").toFile());
        Path repo = workspace.resolve(SandboxLayout.REPO_MOUNT_RELATIVE);
        for (var entry : baseFiles.properties()) {
            String path = entry.getKey().substring("repo/".length());
            assertThat(PracticeCriteriaCaseFixtures.fixtureCommand(
                            workspace,
                            "git",
                            "-C",
                            repo.toString(),
                            "show",
                            change.path("base_sha").asString() + ":" + path))
                    .isEqualTo(entry.getValue().asString());
            if (!supplied.has(entry.getKey())) {
                assertThat(repo.resolve(path)).doesNotExist();
                assertThat(PracticeCriteriaCaseFixtures.fixtureCommand(
                                workspace,
                                "git",
                                "-C",
                                repo.toString(),
                                "diff",
                                "--name-status",
                                change.path("base_sha").asString(),
                                change.path("head_sha").asString(),
                                "--",
                                path))
                        .startsWith("D\t");
                if (renamedTo.containsKey(path)) {
                    assertThat(statuses.get(renamedTo.get(path)))
                            .as("change status of %s", path)
                            .isEqualTo("R");
                } else {
                    assertThat(statuses.get(path))
                            .as("change status of %s", path)
                            .isEqualTo("D");
                }
            }
        }
        for (var entry : supplied.properties()) {
            if (entry.getKey().startsWith("context/") && !entry.getKey().equals("context/metadata.json")) {
                assertThat(workspace.resolve(entry.getKey()))
                        .as("supplied %s in %s", entry.getKey(), scenario.path("id"))
                        .hasContent(entry.getValue().asString());
            }
        }
        var metadata =
                mapper.readTree(workspace.resolve("context/metadata.json").toFile());
        // Every field the content source writes is staged, whatever the case states.
        var defaults = scenario.path("workType").asString().equals("issue")
                ? LiveWorkRecords.issue(PracticeCriteriaCaseFixtures.DEFAULT_TITLE, "")
                : LiveWorkRecords.pullRequest("0".repeat(40), PracticeCriteriaCaseFixtures.DEFAULT_TITLE, "", 1);
        for (var field : defaults.properties()) {
            assertThat(metadata.has(field.getKey()))
                    .as("default %s", field.getKey())
                    .isTrue();
        }
        if (supplied.has("context/metadata.json")) {
            for (var field : mapper.readTree(
                            supplied.path("context/metadata.json").asString())
                    .properties()) {
                assertThat(metadata.get(field.getKey()))
                        .as("supplied metadata %s in %s", field.getKey(), scenario.path("id"))
                        .isEqualTo(field.getValue());
            }
        }
        Path commits = workspace.resolve(PracticeCriteriaCaseFixtures.COMMITS);
        boolean omitted = StreamSupport.stream(scenario.path("omit").spliterator(), false)
                .anyMatch(path -> path.asString().equals(PracticeCriteriaCaseFixtures.COMMITS));
        if (omitted) {
            assertThat(commits).as("an intended missing commit capture").doesNotExist();
            assertThat(paths).doesNotContain(PracticeCriteriaCaseFixtures.COMMITS);
        } else if (!supplied.has(PracticeCriteriaCaseFixtures.COMMITS)) {
            assertThat(mapper.readTree(commits.toFile()).path("commits")).hasSize(1);
        }
        try (var listed = Files.list(workspace.resolve(SandboxLayout.PRACTICES_PREFIX))) {
            List<Path> practices = listed.toList();
            assertThat(practices).hasSize(scenario.path("expected").size() + 1);
            for (Path practice : practices) {
                assertThat(Files.readString(practice))
                        .as("staged %s in %s", practice.getFileName(), scenario.path("id"))
                        .doesNotContain(scenario.path("reason").asString());
            }
        }
    }

    @Test
    void shouldKeepRequiredAndContextualSourcesNonExhaustive() throws Exception {
        var mapper = JsonMapper.builder().build();
        Path workspace = root.resolve("non-exhaustive-source-policy");
        Files.createDirectories(workspace);
        for (String sidecar : List.of("pi-change.ts", "pi-task-paths.ts")) {
            Files.copy(Path.of("src/main/resources/agent", sidecar), workspace.resolve(sidecar));
        }
        var scenario = mapper.valueToTree(Map.of(
                "id",
                "non-exhaustive-source-policy",
                "workType",
                "pull_request",
                "expected",
                Map.of("scope-one-reviewable-change", "UNDETERMINED"),
                "files",
                Map.of("repo/Example.java", "final class Example {}\n")));
        var definitions = PracticeCriteriaCaseFixtures.stage(workspace, scenario);
        LiveRunnerWorkspace.stageFixtureInputs(workspace, "scm.pull_request", definitions);
        var index = mapper.readTree(
                workspace.resolve(SandboxLayout.PRACTICES_PREFIX + "index.json").toFile());
        assertThat(index).hasSize(1);
        assertThat(index.get(0).path("slug").asString()).isEqualTo("scope-one-reviewable-change");
        assertThat(index.get(0).path("exhaustiveSources")).isEmpty();
        assertThat(index.get(0).path("readsSources"))
                .extracting(JsonNode::asString)
                .containsExactly(
                        "scm.pull-request.checks",
                        "scm.pull-request.core",
                        "scm.pull-request.diff",
                        "scm.repository.tree",
                        "scm.review-threads");
        assertThat(Files.readString(workspace.resolve(SandboxLayout.PRACTICES_PREFIX + "index.json")))
                .doesNotContain("UNDETERMINED");
    }

    @Test
    @Timeout(10)
    void shouldCaptureBothStreamsWhenOutputExceedsPipeBuffer() throws Exception {
        String output = PracticeCriteriaCaseFixtures.fixtureCommand(
                root,
                "node",
                "-e",
                "process.stdout.write('o'.repeat(1024 * 1024)); process.stderr.write('e'.repeat(1024 * 1024));");

        assertThat(output).hasSize(2 * 1024 * 1024);
        assertThat(output.chars().filter(value -> value == 'o').count()).isEqualTo(1024 * 1024);
        assertThat(output.chars().filter(value -> value == 'e').count()).isEqualTo(1024 * 1024);
        try (var files = Files.list(root)) {
            assertThat(files.filter(file -> file.getFileName().toString().startsWith("fixture-command-")))
                    .isEmpty();
        }
    }

    @Test
    @Timeout(10)
    void shouldReportCommandAndExitCodeWhenFixtureCommandFails() {
        assertThatThrownBy(() -> PracticeCriteriaCaseFixtures.fixtureCommand(
                        root, "node", "-e", "process.stderr.write('fixture failure'); process.exitCode = 7;"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exit 7", "node -e", "fixture failure");
    }

    @Test
    @Timeout(10)
    void shouldStopFixtureProcessWhenInterrupted() throws Exception {
        Path pidFile = root.resolve("fixture.pid");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var command = executor.submit(() -> PracticeCriteriaCaseFixtures.fixtureCommand(
                    root,
                    "node",
                    "-e",
                    "require('node:fs').writeFileSync('fixture.pid', String(process.pid)); setInterval(() =>"
                            + " {}, 1000);"));
            try {
                await().atMost(5, TimeUnit.SECONDS).until(() -> Files.exists(pidFile) && Files.size(pidFile) > 0);
                var child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile)))
                        .orElseThrow();
                try {
                    command.cancel(true);
                    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                        assertThat(child.isAlive()).isFalse();
                        try (var files = Files.list(root)) {
                            assertThat(files.filter(file ->
                                            file.getFileName().toString().startsWith("fixture-command-")))
                                    .isEmpty();
                        }
                    });
                } finally {
                    child.destroyForcibly();
                }
            } finally {
                command.cancel(true);
            }
        }
    }
}
