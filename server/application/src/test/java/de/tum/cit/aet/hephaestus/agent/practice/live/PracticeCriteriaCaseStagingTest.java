package de.tum.cit.aet.hephaestus.agent.practice.live;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class PracticeCriteriaCaseStagingTest extends BaseUnitTest {

    @TempDir
    Path root;

    @Test
    void shouldStageCompleteSourcesAndShippedCriteriaWithoutReferenceAnswers() throws Exception {
        var mapper = JsonMapper.builder().build();
        var cases = mapper.readTree(
                Path.of("src/test/resources/practices/criteria-cases.json").toFile());
        for (var scenario : cases) {
            Path workspace = root.resolve(scenario.path("id").asString());
            Files.createDirectories(workspace);
            for (String sidecar : List.of("pi-change.ts", "pi-task-paths.ts")) {
                Files.copy(Path.of("src/main/resources/agent", sidecar), workspace.resolve(sidecar));
            }
            PracticeRunnerLiveLlmTest.stageCriteriaCase(workspace, scenario);
            var index = mapper.readTree(
                    workspace.resolve(SandboxLayout.MANIFEST_PATH).toFile());
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
            var files = mapper.readTree(
                            workspace.resolve("work/change/files.json").toFile())
                    .path("files");
            var repositoryFiles = scenario.path("files").properties().stream()
                    .filter(entry -> entry.getKey().startsWith("repo/"))
                    .toList();
            assertThat(files.size()).isEqualTo(repositoryFiles.size());
            assertThat(mapper.readTree(workspace.resolve("context/commits.json").toFile())
                            .path("commits"))
                    .hasSize(1);
            try (var practices = Files.list(workspace.resolve(SandboxLayout.PRACTICES_PREFIX))) {
                assertThat(practices.count())
                        .isEqualTo(scenario.path("expected").size() + 1L);
            }
        }
    }
}
