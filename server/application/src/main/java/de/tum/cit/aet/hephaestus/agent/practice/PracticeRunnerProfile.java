package de.tum.cit.aet.hephaestus.agent.practice;

import de.tum.cit.aet.hephaestus.agent.runtime.PiRunnerProfile;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class PracticeRunnerProfile implements PiRunnerProfile {

    public static final String SCRIPT = "pi-runner.js";

    /** Relative imports require these scripts to share the runner's directory. */
    private static final List<String> SIDECARS = List.of(
            "pi-agent-sandbox.js",
            "pi-assessment-cache.js",
            "pi-session-pace.js",
            "pi-task-paths.js",
            "pi-tool-arguments.js",
            "pi-tool-validation.js",
            "pi-change.js",
            "pi-change-view.js",
            "pi-glob-worker.js",
            "pi-pinned-object.js",
            "pi-search-worker.js",
            "pi-tool-side.js",
            "pi-verified-search.js",
            "tool-client.js",
            "tool-program.js",
            "tool-protocol.js",
            "pi-declared-sources.js",
            "pi-diagnostics.js",
            "pi-diagnostics.json",
            "pi-practice-scripts-report.js",
            "pi-error-text.js",
            "pi-gateway-wait.js",
            "pi-model-failure.js",
            "pi-text.js",
            "pi-result-normalize.js",
            "pi-result-schema.js",
            "pi-result-tools.js",
            "pi-problem-drafts.js",
            "pi-practice-coverage.js",
            "pi-review-brief.js",
            "pi-folder-index.js",
            "pi-review-turns.js",
            "pi-turn-context.js",
            "pi-runner-output.js",
            "pi-runner-usage.js",
            "pi-runner-retry.js",
            "pi-admitted-results.js",
            "pi-session-lifecycle.js",
            "pi-session-reads.js",
            "pi-bash-reads.js",
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
        // The loop starts no process: its tools run on the other sides of the Pod (ADR 0068). A server runs codemode,
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
