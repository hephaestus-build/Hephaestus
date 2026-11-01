package de.tum.cit.aet.hephaestus.agent.runtime;

import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.time.Duration;
import java.util.List;
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

    public static final int PRACTICE_CONTRACT_VERSION = 3;
    public static final String SOURCE_CONTRACT_VERSION = ClasspathSourceCatalogRegistry.CURRENT_VERSION_VALUE;

    /**
     * The sources every review session reads through Git in its checkout: the change and the repository. A practice
     * may cite them without declaring them (ADR 0069). The runner names the same kinds ({@code readableKinds} in
     * {@code pi-declared-sources.ts}).
     */
    public static final Set<SourceKind> READ_THROUGH_GIT =
            Set.of(new SourceKind("scm.pull-request.diff"), new SourceKind("scm.repository.tree"));

    /** Container workspace root. */
    public static final String WORKSPACE_ROOT = "/workspace";

    /** Each scratch place, {@code /tmp} and {@code ~/.local}: a bounded disk volume in Kubernetes. */
    public static final long SCRATCH_BYTES = 1024L * 1024 * 1024;

    // ── Job folder (ADR 0041): read-only inputs and separate writable mounts ──────────────────────────────
    //   context/, repos/, inputs/, task.json, INDEX.* — the read-only job folder
    //   work/    — scratch the agent + practice scripts write during the run; NEVER collected
    //   out/     — the ONLY directory collected back into SQL
    //   .pi/     — the Pi SDK runtime home (vendor dir)

    /** Workspace-relative prefix for the read-only input subtree. */
    public static final String INPUTS_PREFIX = "inputs/";

    public static final String REPO_MOUNT = WORKSPACE_ROOT + "/repos/reviewed";

    /** Workspace-relative prefix the agent cites for repo files ({@code repos/reviewed/<path>}). */
    public static final String REPO_MOUNT_RELATIVE = "repos/reviewed/";

    /** Output directory the sandbox collects after the run. */
    public static final String OUTPUT_PREFIX = "out/";

    public static final String OUTPUT_PATH = WORKSPACE_ROOT + "/out";

    /** Workspace-relative filename of the task envelope ({@code task.json}). */
    public static final String TASK_ENVELOPE_FILENAME = "task.json";

    /** Workspace-relative prefix every {@link ContentSource} must write under. */
    public static final String CONTEXT_PREFIX = "context/";

    public static final String MANIFEST_PATH = "INDEX.json";

    /**
     * Path of the feedback-composition request among the files the worker's composer reads: which destinations it
     * may write for and the bounds each must respect. A sandbox is never given one.
     */
    public static final String FEEDBACK_COMPOSITION_PATH = INPUTS_PREFIX + "feedback-composition.json";

    public static final String PRACTICE_SCRIPT_REPORT_FILE = "practice-scripts.json";

    /**
     * Workspace-relative prefix for what earlier reviews recorded and already said.
     *
     * <p>Separate from {@link #CONTEXT_PREFIX}, which contains the work under review. A history path
     * identifies authorized records from earlier reviews when staged; naming the path does not
     * establish availability or complete coverage.
     */
    public static final String HISTORY_PREFIX = INPUTS_PREFIX + "history/";

    /** Workspace-relative prefix for per-practice catalog files (index, criteria). */
    public static final String PRACTICES_PREFIX = INPUTS_PREFIX + "practices/";

    /**
     * Workspace-relative prefix for the writable scratch region (ADR 0020). Everything the agent +
     * practice script write during a run lives here; the sandbox makes this subtree writable by the
     * container uid while {@link #INPUTS_PREFIX} stays read-only. Never collected back into SQL.
     */
    public static final String WORK_PREFIX = "work/";

    public static final String PRACTICE_SCRIPT_PREFIX = WORK_PREFIX + "practice-scripts/";

    /**
     * Workspace-relative prefix of the per-practice inputs: for every assigned practice {@code <slug>.inputs.json}, its frozen scope, standard and previous result
     * or {@code null}; {@code <slug>.ts} only for a practice that holds its own script. {@code lib/contract.ts} owns
     * their shape.
     */
    public static final String PRACTICE_SCRIPT_PRACTICES_PREFIX = PRACTICE_SCRIPT_PREFIX + "practices/";

    public static final String PRACTICE_SCRIPT_LIBRARY_LINK = PRACTICE_SCRIPT_PREFIX + "lib";

    public static final String PRACTICE_SCRIPT_INPUTS_SUFFIX = ".inputs.json";

    public static final String PRACTICE_SCRIPT_IMAGE_ROOT = "/opt/practice-scripts";
    public static final String PRACTICE_SCRIPT_LIBRARY_TARGET = PRACTICE_SCRIPT_IMAGE_ROOT + "/lib";
    public static final String PRACTICE_SCRIPT_KITS_LINK = PRACTICE_SCRIPT_PREFIX + "kits";
    public static final String PRACTICE_SCRIPT_KITS_TARGET = PRACTICE_SCRIPT_IMAGE_ROOT + "/kits";

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

    public static final String PRACTICE_SCRIPT_MODELS_FILE = "practice-script-models.json";

    /** Workspace-relative filename of the shared provider-registration ES module both runners import. */
    public static final String PROVIDER_HELPER_FILENAME = "pi-provider.ts";

    /** Workspace-relative filename of the orchestrator instructions loaded into Pi at runtime. */
    public static final String ORCHESTRATOR_FILENAME = "AGENTS.md";

    /** Workspace-relative path of the orchestrator instructions (combination of prefix + filename). */
    public static final String ORCHESTRATOR_PATH = PI_AGENT_PREFIX + ORCHESTRATOR_FILENAME;

    /** Workspace-relative directory for Pi SDK session JSONL files. */
    public static final String SESSIONS_DIR = ".sessions";

    /**
     * Exit code emitted by the Pi runner when it cannot use {@code task.json}: the file is missing or unreadable, its
     * {@code schemaVersion} is not the image's, or a task field is invalid.
     */
    public static final int EXIT_ENVELOPE_MISMATCH = 42;

    /** The result was not acknowledged before the upload deadline. */
    public static final int EXIT_UPLOAD_FAILED = 43;

    /** The child was stopped at the work deadline; upload still had its own window. */
    public static final int EXIT_WORK_TIMEOUT = 124;

    /** The exit of a process that SIGKILL ended: 128 plus the signal's number, as every container runtime reports it. */
    public static final int EXIT_KILLED = 137;

    /** The sandbox's first process: it fills {@code /workspace} from the attempt's gateway session. */
    public static final List<String> INITIALIZER_COMMAND =
            List.of("node", "--enable-source-maps", "/opt/pi-sdk/gateway-init.js");

    /**
     * The wrapper of a practice review's own command: it holds the gateway credential and uploads the result. A process
     * of the sandbox must not open its inspector with SIGUSR1.
     */
    public static final List<String> RUNTIME_COMMAND =
            List.of("node", "--disable-sigusr1", "--enable-source-maps", "/opt/pi-sdk/gateway-run.js");

    public static final Duration RESULT_UPLOAD_GRACE = Duration.ofMinutes(10);

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
    public static final int RUNTIME_CONTRACT_VERSION = 9;

    /** Node major required by the staged runners. */
    public static final int NODE_MAJOR = 24;

    /** Pi SDK major required by the staged runners. */
    public static final int PI_SDK_MAJOR = 1;

    /** Practice slug pattern enforced at the handler boundary as defense-in-depth against FS-path injection. */
    public static final Pattern PRACTICE_SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    /** Exact workspace paths an adapter may pass in {@link PiPlanSpec#extraInputs()}. */
    public static Set<String> allowedExtraInputPaths() {
        return Set.of(PRACTICE_SCRIPT_MODELS_FILE);
    }
}
