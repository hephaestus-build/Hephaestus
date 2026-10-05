package de.tum.cit.aet.hephaestus.agent.practice.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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

    static Stream<JsonNode> criteriaCases() throws IOException {
        var cases = JsonMapper.builder()
                .build()
                .readTree(Path.of("src/test/resources/practices/criteria-cases.json")
                        .toFile());
        return StreamSupport.stream(cases.spliterator(), false);
    }

    @ParameterizedTest(name = "criteria case {index}")
    @MethodSource("criteriaCases")
    void shouldStageCompleteSourcesAndShippedCriteriaWithoutReferenceAnswers(JsonNode scenario) throws Exception {
        var mapper = JsonMapper.builder().build();
        Path workspace = root.resolve(scenario.path("id").asString());
        Files.createDirectories(workspace);
        for (String sidecar : List.of("pi-change.ts", "pi-task-paths.ts")) {
            Files.copy(Path.of("src/main/resources/agent", sidecar), workspace.resolve(sidecar));
        }
        PracticeCriteriaCaseFixtures.stage(workspace, scenario);
        var index =
                mapper.readTree(workspace.resolve(SandboxLayout.MANIFEST_PATH).toFile());
        var paths = new HashSet<String>();
        for (var artifact : index.path("artifacts")) {
            String path = artifact.path("artifact").path("path").asString();
            assertThat(paths.add(path))
                    .as("unique artifact in %s", scenario.path("id"))
                    .isTrue();
            assertThat(workspace.resolve(path)).exists();
        }
        var task = mapper.readTree(
                workspace.resolve(SandboxLayout.TASK_ENVELOPE_FILENAME).toFile());
        assertThat(task.path("repositoryRoot").asString())
                .isEqualTo(SandboxLayout.REPO_MOUNT_RELATIVE.replaceFirst("/$", ""));
        assertThat(task.path("prompt").asString())
                .doesNotContain(scenario.path("reason").asString());
        var files = mapper.readTree(workspace.resolve("work/change/files.json").toFile())
                .path("files");
        var repositoryFiles = scenario.path("files").properties().stream()
                .filter(entry -> entry.getKey().startsWith("repo/"))
                .toList();
        assertThat(files.size()).isEqualTo(repositoryFiles.size());
        assertThat(mapper.readTree(workspace.resolve("context/commits.json").toFile())
                        .path("commits"))
                .hasSize(1);
        try (var practices = Files.list(workspace.resolve(SandboxLayout.PRACTICES_PREFIX))) {
            assertThat(practices.count()).isEqualTo(scenario.path("expected").size() + 1L);
        }
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
            var command = executor.submit(
                    () -> PracticeCriteriaCaseFixtures.fixtureCommand(
                            root,
                            "node",
                            "-e",
                            "require('node:fs').writeFileSync('fixture.pid', String(process.pid)); setInterval(() => {}, 1000);"));
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
