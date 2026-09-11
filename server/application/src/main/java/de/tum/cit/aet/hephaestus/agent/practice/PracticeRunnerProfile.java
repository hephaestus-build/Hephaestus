package de.tum.cit.aet.hephaestus.agent.practice;

import de.tum.cit.aet.hephaestus.agent.runtime.PiRunnerProfile;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.util.List;
import java.util.Map;

public final class PracticeRunnerProfile implements PiRunnerProfile {

    public static final String SCRIPT = "pi-runner.ts";

    /** Relative imports require these scripts to share the runner's directory. */
    private static final List<String> SIDECARS = List.of(
            "pi-agent-sandbox.ts",
            "pi-task-paths.ts",
            "pi-precompute.ts",
            "pi-precompute.sh",
            "pi-error-text.ts",
            "pi-observation-normalize.ts",
            "pi-practice-coverage.ts",
            "pi-runner-output.ts",
            "pi-runner-usage.ts",
            "pi-runner-timings.ts",
            "pi-runner-recording-pace.ts",
            "pi-runner-retry.ts",
            "pi-runner-composition.ts",
            "pi-review-tree.ts",
            "pi-session-tree.ts",
            "pi-session-lifecycle.ts",
            SandboxLayout.PROVIDER_HELPER_FILENAME);

    private static final List<String> PROMPTS = List.of(SandboxLayout.FEEDBACK_COMPOSER_PROMPT_FILENAME);

    @Override
    public String runnerScript() {
        return SCRIPT;
    }

    @Override
    public List<String> sidecarScripts() {
        return SIDECARS;
    }

    @Override
    public List<String> promptResources() {
        return PROMPTS;
    }

    @Override
    public List<String> runtimeFlags() {
        // Subprocesses bypass Node permissions; read-only mounts and container isolation protect evidence.
        return List.of("--max-old-space-size=256");
    }

    @Override
    public Map<String, String> additionalEnv() {
        return Map.of();
    }
}
