package de.tum.cit.aet.hephaestus.agent.runtime;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Per-runner-kind strategy. Each adapter owns its implementation in the adjacent domain package
 * and passes it to {@link PiRuntimeFactory#build(PiPlanSpec)}. Profiles are pure value objects —
 * they MUST NOT capture spec-level state (provider, credentials, baseUrl).
 */
public interface PiRunnerProfile {
    /** Node's temporary directory in the agent image, which {@link PiRuntimeFactory} sets {@code TMPDIR} to. */
    String AGENT_TMPDIR = "/home/agent/.local/tmp";

    /**
     * Directories the runner may write.
     *
     * <p>A write grant is resolved when Node starts, so a directory created afterwards accepts its own
     * {@code mkdir} and then denies every write inside it. {@link PiRuntimeFactory} therefore creates
     * all of these before the runner starts, and {@link #BASE_RUNTIME_FLAGS} grants exactly these, so
     * neither half can name a path the other does not.
     *
     * <p>A profile's {@link #setupCommands()} run after these exist and before Node starts.
     */
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

    List<String> BASE_RUNTIME_FLAGS = Stream.concat(
                    Stream.of(
                            "--max-old-space-size=256",
                            "--permission",
                            "--allow-fs-read=" + SandboxLayout.WORKSPACE_ROOT,
                            "--allow-fs-read=/opt/pi-sdk",
                            // The SDK looks for user-level skills under $HOME (/home/agent in the image) on
                            // every session; that directory holds nothing, and a read the permission model
                            // denies is a thrown error, not an absence, so it must be readable.
                            "--allow-fs-read=/home/agent"),
                    WRITABLE_DIRECTORIES.stream().map(directory -> "--allow-fs-write=" + directory))
            .toList();
    /** Runner script filename under {@code resources/agent/}. */
    String runnerScript();

    /**
     * Sibling ES-module files the runner imports relatively (e.g. {@code ./pi-result-normalize.ts}).
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

    /**
     * Trusted shell commands that set up the workspace after {@link #WRITABLE_DIRECTORIES} exist and before Node
     * starts, such as a link Node under {@code --permission} could not create. Emitted verbatim: each must be
     * shell-safe and fixed, never built from job data. Both runners use the shared JSON helpers.
     */
    default List<String> setupCommands() {
        return PRACTICE_SCRIPT_LIBRARY_SETUP;
    }

    /**
     * Links the image's practice-script library into the workspace, where {@code pi-provider.ts} and the runners import
     * the shared JSON helpers from. The Heph host runs it too.
     */
    List<String> PRACTICE_SCRIPT_LIBRARY_SETUP = List.of(
            "mkdir -p " + SandboxLayout.WORKSPACE_ROOT + "/" + SandboxLayout.PRACTICE_SCRIPT_PREFIX,
            "ln -sfn " + SandboxLayout.PRACTICE_SCRIPT_LIBRARY_TARGET + " " + SandboxLayout.WORKSPACE_ROOT + "/"
                    + SandboxLayout.PRACTICE_SCRIPT_LIBRARY_LINK);
}
