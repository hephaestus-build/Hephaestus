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

        assertThat(body)
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

        assertThat(body)
                .as("runner reads the practices index under SandboxLayout.PRACTICES_PREFIX")
                .contains("/" + SandboxLayout.PRACTICES_PREFIX + "index.json");
    }

    @Test
    @DisplayName("pi-orchestrator.md cites the repo mount at SandboxLayout.REPO_MOUNT_RELATIVE (prompt↔ABI pin)")
    void orchestratorPromptCitesRepoMountFromAbi() throws IOException {
        // The orchestrator prompt tells the LIVE agent where to read the repo checkout. That path is NOT
        // pinned by the runner sync above, so a rename of the sandbox source region (e.g. worktrees→sources)
        // could silently rot the prompt — shipping CI-green while pointing the agent at a dead path and
        // losing all surrounding-code context on every real SCM review. Pin it to the ABI constant so the
        // next rename must update both in lockstep or this test fails.
        Path orchestrator = resolveResource("agent/pi-orchestrator.md");
        assertThat(orchestrator).isRegularFile();
        String body = Files.readString(orchestrator, StandardCharsets.UTF_8);

        assertThat(body)
                .as("orchestrator prompt references the repo mount at SandboxLayout.REPO_MOUNT_RELATIVE")
                .contains(SandboxLayout.REPO_MOUNT_RELATIVE);
        assertThat(body).doesNotContain("evidence.locations", "evidence.location", "evidence.snippets");
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
