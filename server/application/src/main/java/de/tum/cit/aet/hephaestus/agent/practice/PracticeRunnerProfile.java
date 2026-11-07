package de.tum.cit.aet.hephaestus.agent.practice;

import de.tum.cit.aet.hephaestus.agent.runtime.PiRunnerProfile;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class PracticeRunnerProfile implements PiRunnerProfile {

    public static final String SCRIPT = "pi-runner.ts";

    /** Relative imports require these scripts to share the runner's directory. */
    private static final List<String> SIDECARS = List.of(
            "pi-agent-sandbox.ts",
            "pi-assessment-cache.ts",
            "pi-session-pace.ts",
            "pi-task-paths.ts",
            "pi-tool-arguments.ts",
            "pi-tool-validation.ts",
            "pi-change.ts",
            "pi-change-view.ts",
            "pi-pinned-object.ts",
            "pi-search-worker.ts",
            "pi-tool-side.ts",
            "pi-verified-search.ts",
            "tool-client.ts",
            "tool-program.ts",
            "tool-protocol.ts",
            "pi-declared-sources.ts",
            "pi-diagnostics.ts",
            "pi-diagnostics.json",
            "pi-practice-scripts-report.ts",
            "pi-error-text.ts",
            "pi-model-failure.ts",
            "pi-text.ts",
            "pi-result-normalize.ts",
            "pi-result-schema.ts",
            "pi-result-tools.ts",
            "pi-problem-drafts.ts",
            "pi-practice-coverage.ts",
            "pi-review-brief.ts",
            "pi-folder-index.ts",
            "pi-review-turns.ts",
            "pi-turn-context.ts",
            "pi-runner-output.ts",
            "pi-runner-usage.ts",
            "pi-runner-retry.ts",
            "pi-admitted-results.ts",
            "pi-session-lifecycle.ts",
            "pi-session-reads.ts",
            "pi-bash-reads.ts",
            SandboxLayout.PROVIDER_HELPER_FILENAME);

    @Override
    public String runnerScript() {
        return SCRIPT;
    }

    @Override
    public List<String> sidecarScripts() {
        return SIDECARS;
    }

    /** Where the runner writes the prepared input of a practice program's review. */
    private static final String REVIEW_INPUT_DIRECTORY =
            SandboxLayout.WORKSPACE_ROOT + "/" + SandboxLayout.WORK_PREFIX + "review-input";

    /** The runner's heap. Its sessions live in it, so it also bounds how many run at once. */
    public static final int HEAP_MIB = 256;

    @Override
    public List<String> runtimeFlags() {
        // The loop starts no process: its tools run on the other sides of the Pod (ADR 0068). A worker runs codemode,
        // and the loop's own proof of a search.
        return Stream.concat(
                        BASE_RUNTIME_FLAGS.stream(),
                        Stream.of(
                                "--allow-fs-read=" + SandboxLayout.PRACTICE_SCRIPT_IMAGE_ROOT,
                                "--allow-fs-write=" + REVIEW_INPUT_DIRECTORY,
                                "--allow-worker"))
                .toList();
    }

    @Override
    public Map<String, String> additionalEnv() {
        return Map.of();
    }

    @Override
    public List<String> setupCommands() {
        return Stream.concat(
                        PiRunnerProfile.super.setupCommands().stream(),
                        Stream.of(
                                "ln -sfn " + SandboxLayout.PRACTICE_SCRIPT_KITS_TARGET + " "
                                        + SandboxLayout.WORKSPACE_ROOT + "/" + SandboxLayout.PRACTICE_SCRIPT_KITS_LINK,
                                // A write grant holds only for a directory that exists when Node starts.
                                "mkdir -p " + REVIEW_INPUT_DIRECTORY))
                .toList();
    }
}
