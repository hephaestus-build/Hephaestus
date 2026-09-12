package de.tum.cit.aet.hephaestus.agent.runtime;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared constants for the Pi sandbox workspace ABI documented at
 * {@code docs/contributor/agent/workspace-abi.mdx}. Avoid duplicating these literals —
 * they are the wire contract between the server-side workspace materialiser and the in-container
 * runner.
 */
public final class SandboxLayout {

    private SandboxLayout() {}

    /** Container workspace root. */
    public static final String WORKSPACE_ROOT = "/workspace";

    // ── Layout (ADR 0020): read-only vs writable by LOCATION, not lore ──────────────────────────────
    //   inputs/  — EVERYTHING the agent may only read (the /workspace volume is mounted read-only)
    //   work/    — scratch the agent + precompute write during the run; NEVER collected
    //   out/     — the ONLY directory collected back into SQL
    //   .pi/     — the Pi SDK runtime home (vendor dir)

    /** Workspace-relative prefix for the read-only input subtree. */
    public static final String INPUTS_PREFIX = "inputs/";

    public static final String SOURCES_PREFIX = INPUTS_PREFIX + "sources/";

    public static String sourceMount(String originId) {
        return SOURCES_PREFIX + originId + "/";
    }

    public static final String SCM_SOURCE_KEEP = sourceMount("scm") + ".keep";

    public static final String REPO_MOUNT = WORKSPACE_ROOT + "/" + sourceMount("scm") + "repo";

    /** Workspace-relative prefix the agent cites for repo files ({@code inputs/sources/scm/repo/<path>}). */
    public static final String REPO_MOUNT_RELATIVE = sourceMount("scm") + "repo/";

    /** Output directory the sandbox collects after the run. */
    public static final String OUTPUT_PREFIX = "out/";

    public static final String OUTPUT_PATH = WORKSPACE_ROOT + "/out";

    /** Workspace-relative filename of the task envelope ({@code task.json}). */
    public static final String TASK_ENVELOPE_FILENAME = "task.json";

    /** Workspace-relative prefix every {@link de.tum.cit.aet.hephaestus.agent.context.ContentSource} must write under. */
    public static final String CONTEXT_PREFIX = INPUTS_PREFIX + "context/";

    public static final String MANIFEST_PATH = INPUTS_PREFIX + "manifest.json";

    /**
     * Workspace-relative path of the feedback-composition request: whether this run should compose
     * feedback once its measurements are final, which lanes it may write for, and the bounds each must
     * respect.
     *
     * <p>Absence is the off switch, and it is data rather than an env flag on purpose — the handler that
     * knows whether a run is a live measurement of somebody's current work is the same handler that
     * writes this file, so the decision and its parameters travel together.
     */
    public static final String FEEDBACK_COMPOSITION_PATH = INPUTS_PREFIX + "feedback-composition.json";

    /** Workspace-relative filename of the composition stage's output, collected from {@link #OUTPUT_PATH}. */
    public static final String FEEDBACK_FILENAME = "feedback.json";

    /** Workspace-relative filename of the composition stage's instructions, staged beside the runner. */
    public static final String FEEDBACK_COMPOSER_PROMPT_FILENAME = "feedback-composer.md";

    /**
     * Workspace-relative prefix for what earlier reviews recorded and already said.
     *
     * <p>Separate from {@link #CONTEXT_PREFIX} because it is the one part of the sandbox that is not
     * about the artifact under review: {@code inputs/context/} is this event, {@code inputs/history/} is
     * every event before it. Both files below it are always present — an empty one is the review saying
     * it looked and there was nothing, which is a different fact from never having looked.
     */
    public static final String HISTORY_PREFIX = INPUTS_PREFIX + "history/";

    /** Workspace-relative prefix for per-practice catalog files (index, criteria). */
    public static final String PRACTICES_PREFIX = INPUTS_PREFIX + "practices/";

    /**
     * Workspace-relative prefix for the writable scratch region (ADR 0020). Everything the agent +
     * precompute write during a run lives here; the sandbox makes this subtree writable by the
     * container uid while {@link #INPUTS_PREFIX} stays read-only. Never collected back into SQL.
     */
    public static final String WORK_PREFIX = "work/";

    /** Workspace-relative prefix for per-practice precompute scripts injected from the database. */
    public static final String PRECOMPUTE_PREFIX = WORK_PREFIX + "precompute/";

    /** Workspace-relative prefix for runtime precompute output (logs, structured hints). */
    public static final String PRECOMPUTE_OUT_PREFIX = WORK_PREFIX + "precompute-out/";

    /** Workspace-relative prefix for analysis markers ({@link #ANALYSIS_PRACTICES_PREFIX} is a child). */
    public static final String ANALYSIS_PREFIX = WORK_PREFIX + "analysis/";

    /** Workspace-relative path of the practices-analysis marker directory. */
    public static final String ANALYSIS_PRACTICES_PREFIX = ANALYSIS_PREFIX + "practices/";

    /** Workspace-relative directory name of the Pi SDK agent dir. */
    private static final String PI_AGENT_NAME = ".pi";

    /** Workspace-relative path of the Pi SDK agent dir — settings.json, AGENTS.md, extensions/. */
    public static final String PI_AGENT_PREFIX = PI_AGENT_NAME + "/";

    /** Absolute container path of the Pi SDK agent dir — value of {@code PI_CODING_AGENT_DIR}. */
    public static final String PI_AGENT_DIR = WORKSPACE_ROOT + "/" + PI_AGENT_NAME;

    /** Workspace-relative filename of the runner script copied from the classpath. */
    public static final String RUNNER_SCRIPT_FILENAME = ".run-pi.ts";

    /**
     * Workspace-relative filename of the non-secret LLM-provider spec written by
     * {@link PiRuntimeFactory#buildProviderConfigJson}: wire protocol, upstream model id, capability
     * envelope. The proxy URL is not in it — that arrives as {@code $LLM_PROXY_URL}.
     */
    public static final String PROVIDER_CONFIG_FILENAME = "pi-provider.json";

    public static final String NODE_PACKAGE_JSON_FILENAME = "package.json";

    /** Workspace-relative filename of the shared provider-registration ES module both runners import. */
    public static final String PROVIDER_HELPER_FILENAME = "pi-provider.ts";

    /** Workspace-relative filename of the orchestrator instructions loaded into Pi at runtime. */
    public static final String ORCHESTRATOR_FILENAME = "AGENTS.md";

    /** Workspace-relative path of the orchestrator instructions (combination of prefix + filename). */
    public static final String ORCHESTRATOR_PATH = PI_AGENT_PREFIX + ORCHESTRATOR_FILENAME;

    /** Mentor system prompt path — pinned by {@code SandboxLayoutSyncTest} against pi-mentor-runner.ts. */
    public static final String MENTOR_SYSTEM_PROMPT_PATH = "agent/mentor/system.md";

    /** Workspace-relative directory for restored Pi SDK session JSONL files (matches the mentor runner's {@code SESSIONS_DIR}). */
    public static final String SESSIONS_DIR = ".sessions";

    public static final String SESSIONS_DIR_PREFIX = SESSIONS_DIR + "/";

    /** Exit code emitted by the Pi runner on envelope/image drift (unsupported {@code schemaVersion} or {@code kind}). */
    public static final int EXIT_ENVELOPE_MISMATCH = 42;

    /**
     * Exit code emitted by the Pi runner when the review finished but the call admitting it never
     * reached this server — the sandbox holds the app server's address as it was when the container
     * started, so a server that came back somewhere else is unreachable for the rest of the run. The
     * work is requeued rather than ended, since the same review against a reachable server is the
     * outcome that was wanted.
     */
    public static final int EXIT_SERVER_UNREACHABLE = 75;

    /**
     * Exit code emitted by the Pi runner when it reached no practice and every model call it made went
     * unanswered. Nothing about the reviewed work was measured, so there is nothing to record and
     * nothing a second attempt would repeat — the work is requeued, like an unreachable server.
     */
    public static final int EXIT_PROVIDER_UNREACHABLE = 76;

    /** OCI label declaring the agent image runtime contract. */
    public static final String RUNTIME_CONTRACT_LABEL = "hephaestus.agent.runtime-contract";

    /** Runtime contract required by the staged runners. */
    public static final int RUNTIME_CONTRACT_VERSION = 3;

    /** Node major required by the staged runners. */
    public static final int NODE_MAJOR = 24;

    /** Pi SDK major required by the staged runners. */
    public static final int PI_SDK_MAJOR = 0;

    /** Practice slug pattern enforced at the handler boundary as defense-in-depth against FS-path injection. */
    public static final Pattern PRACTICE_SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    /** Exact workspace paths an adapter may pass in {@link PiPlanSpec#extraInputs()}. */
    public static Set<String> allowedExtraInputPaths() {
        return Set.of(MENTOR_SYSTEM_PROMPT_PATH);
    }

    /** Workspace-path prefixes accepted by {@link PiPlanSpec#extraInputs()} for dynamic-suffix paths. */
    public static Set<String> allowedExtraInputPrefixes() {
        return Set.of(CONTEXT_PREFIX, SESSIONS_DIR_PREFIX);
    }
}
