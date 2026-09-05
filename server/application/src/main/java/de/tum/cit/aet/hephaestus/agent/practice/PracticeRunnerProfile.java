package de.tum.cit.aet.hephaestus.agent.practice;

import de.tum.cit.aet.hephaestus.agent.runtime.PiRunnerProfile;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.util.List;
import java.util.Map;

public final class PracticeRunnerProfile implements PiRunnerProfile {

    public static final String SCRIPT = "pi-runner.ts";

    /** Imported by {@link #SCRIPT} with a relative specifier, so each must be staged beside it. */
    private static final List<String> SIDECARS = List.of(
            "pi-agent-sandbox.ts",
            "pi-error-text.ts",
            "pi-grep-tool.ts",
            "pi-observation-normalize.ts",
            "pi-practice-coverage.ts",
            "pi-runner-output.ts",
            "pi-runner-usage.ts",
            "pi-runner-timings.ts",
            "pi-runner-composition.ts",
            "pi-review-tree.ts",
            "pi-session-tree.ts",
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
    public Map<String, String> additionalEnv() {
        return Map.of();
    }
}
