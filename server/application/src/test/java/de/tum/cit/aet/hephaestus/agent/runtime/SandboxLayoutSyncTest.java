package de.tum.cit.aet.hephaestus.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelope;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cross-language sync test. Pins the runner's hardcoded {@code task.json}, exit-{@code 42}, and
 * {@code schemaVersion} literals against the {@link SandboxLayout} and {@link TaskEnvelope}
 * constants the Java side uses. The Java side itself is single-sourced from {@code SandboxLayout};
 * this test catches drift only on the JavaScript runner.
 */
class SandboxLayoutSyncTest extends BaseUnitTest {

    @Test
    void runnerLiteralsMatchAbi() throws IOException {
        Path runner = resolveResource("agent/pi-runner.ts");
        assertThat(runner).isRegularFile();
        String body = Files.readString(runner, StandardCharsets.UTF_8);

        assertThat(body)
                .as("runner defaults its root to SandboxLayout.WORKSPACE_ROOT")
                .contains("\"" + SandboxLayout.WORKSPACE_ROOT + "\"");

        assertThat(body)
                .as("runner reads the task envelope at SandboxLayout.TASK_ENVELOPE_FILENAME")
                .contains("/" + SandboxLayout.TASK_ENVELOPE_FILENAME);

        assertThat(Files.readString(resolveResource("agent/pi-task-paths.ts")))
                .as("runner pins SUPPORTED_SCHEMA_VERSION to the Java SCHEMA_VERSION constant")
                .contains("SUPPORTED_SCHEMA_VERSION = " + TaskEnvelope.SCHEMA_VERSION);

        assertThat(body)
                .as("runner pins ENVELOPE_MISMATCH_EXIT to SandboxLayout.EXIT_ENVELOPE_MISMATCH")
                .contains("ENVELOPE_MISMATCH_EXIT = " + SandboxLayout.EXIT_ENVELOPE_MISMATCH);

        assertThat(body)
                .as("runner pins SERVER_UNREACHABLE_EXIT to SandboxLayout.EXIT_SERVER_UNREACHABLE")
                .contains("SERVER_UNREACHABLE_EXIT = " + SandboxLayout.EXIT_SERVER_UNREACHABLE);

        assertThat(body)
                .as("runner pins PROVIDER_UNREACHABLE_EXIT to SandboxLayout.EXIT_PROVIDER_UNREACHABLE")
                .contains("PROVIDER_UNREACHABLE_EXIT = " + SandboxLayout.EXIT_PROVIDER_UNREACHABLE);

        assertThat(body)
                .as("runner writes its output under SandboxLayout.OUTPUT_PATH")
                .contains(SandboxLayout.OUTPUT_PATH.substring(SandboxLayout.WORKSPACE_ROOT.length()));

        assertThat(body).as("runner uses the task-declared practice index").contains("INPUT_PATHS.practiceIndex");
    }

    @Test
    void shouldResolvePromptLocationsFromTheTask() throws IOException {
        for (String prompt : new String[] {"pi-orchestrator.md", "feedback-composer.md"}) {
            String body = Files.readString(resolveResource("agent/" + prompt));
            assertThat(body).contains("task.json.paths", "<practiceIndex>").doesNotContain("inputs/");
        }
    }

    @Test
    @DisplayName(
            "pi-mentor-runner.ts pins MENTOR_SYSTEM_PROMPT_PATH, SESSIONS_DIR_PREFIX, PI_AGENT_DIR, and the envelope exit")
    void mentorRunnerLiteralsMatchAbi() throws IOException {
        Path runner = resolveResource("agent/pi-mentor-runner.ts");
        assertThat(runner).isRegularFile();
        String body = Files.readString(runner, StandardCharsets.UTF_8);

        assertThat(body)
                .as("mentor runner references SandboxLayout.MENTOR_SYSTEM_PROMPT_PATH literally")
                .contains("\"" + SandboxLayout.MENTOR_SYSTEM_PROMPT_PATH + "\"");

        assertThat(body)
                .as("mentor runner references SandboxLayout.SESSIONS_DIR_PREFIX dir name (.sessions)")
                .contains("/" + SandboxLayout.SESSIONS_DIR);

        assertThat(body)
                .as("mentor runner falls back to SandboxLayout.PI_AGENT_DIR when PI_CODING_AGENT_DIR is unset")
                .contains("\"" + SandboxLayout.PI_AGENT_DIR + "\"");

        assertThat(body)
                .as("mentor runner pins ENVELOPE_MISMATCH_EXIT to SandboxLayout.EXIT_ENVELOPE_MISMATCH")
                .contains("ENVELOPE_MISMATCH_EXIT = " + SandboxLayout.EXIT_ENVELOPE_MISMATCH);
    }

    private static Path resolveResource(String relativePath) {
        Path candidate = Path.of("src/main/resources").resolve(relativePath);
        return Files.exists(candidate)
                ? candidate
                : Path.of("server/application/src/main/resources").resolve(relativePath);
    }
}
