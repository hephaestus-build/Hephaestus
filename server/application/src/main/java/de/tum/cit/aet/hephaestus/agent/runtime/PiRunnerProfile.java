package de.tum.cit.aet.hephaestus.agent.runtime;

import java.util.List;
import java.util.Map;

/**
 * Per-runner-kind strategy. Each adapter owns its implementation in the adjacent domain package
 * and passes it to {@link PiRuntimeFactory#build(PiPlanSpec)}. Profiles are pure value objects —
 * they MUST NOT capture spec-level state (provider, credentials, baseUrl).
 */
public interface PiRunnerProfile {
    /**
     * The runner's {@code HOME}. A sandbox's root filesystem is read-only, so its home is on the sandbox's {@code /tmp}
     * volume, as the SandboxTemplate sets it ({@code deploy/chart/hephaestus/templates/sandbox-templates.yaml}).
     */
    String AGENT_HOME = "/tmp/home";

    /** Node's temporary directory, which {@link PiRuntimeFactory} sets {@code TMPDIR} to. */
    String AGENT_TMPDIR = AGENT_HOME + "/.local/tmp";

    /** Directories created before a review starts. */
    List<String> WRITABLE_DIRECTORIES = List.of(
            // The SDK takes a lock directory beside settings.json and auth.json and keeps a models
            // store in the agent dir; without this every session dies at its first model turn.
            SandboxLayout.PI_AGENT_DIR,
            // The SDK writes one JSONL file per session, from the session's first message on.
            SandboxLayout.WORKSPACE_ROOT + "/" + SandboxLayout.SESSIONS_DIR,
            // pi-runner.ts writes work/notes/review.md, its record of what the review recorded.
            SandboxLayout.WORKSPACE_ROOT + "/" + SandboxLayout.WORK_PREFIX + "notes",
            SandboxLayout.OUTPUT_PATH,
            AGENT_TMPDIR);

    List<String> BASE_RUNTIME_FLAGS = List.of("--max-old-space-size=256");

    /** Runner script filename under {@code resources/agent/}. */
    String runnerScript();

    /**
     * Sibling ES-module files the runner imports relatively (e.g. {@code ./pi-result-normalize.js}).
     * Each is staged at the workspace root next to the runner so the import resolves. Empty by default.
     */
    default List<String> sidecarScripts() {
        return List.of();
    }

    /** Flags for the {@code node} invocation. */
    default List<String> runtimeFlags() {
        return BASE_RUNTIME_FLAGS;
    }

    /** {@code KEY=value} pairs scoped to the {@code node} invocation only — not image-wide ENV. */
    Map<String, String> additionalEnv();

    /** Fixed shell commands that prepare the workspace before its runner starts. */
    default List<String> setupCommands() {
        return PRACTICE_SCRIPT_LIBRARY_SETUP;
    }

    /**
     * Links the image's practice-script library into the workspace, where {@code pi-provider.ts} and the runners import
     * the shared JSON helpers from.
     */
    List<String> PRACTICE_SCRIPT_LIBRARY_SETUP = List.of(
            "mkdir -p " + SandboxLayout.WORKSPACE_ROOT + "/" + SandboxLayout.PRACTICE_SCRIPT_PREFIX,
            "ln -sfn " + SandboxLayout.PRACTICE_SCRIPT_LIBRARY_TARGET + " " + SandboxLayout.WORKSPACE_ROOT + "/"
                    + SandboxLayout.PRACTICE_SCRIPT_LIBRARY_LINK);
}
