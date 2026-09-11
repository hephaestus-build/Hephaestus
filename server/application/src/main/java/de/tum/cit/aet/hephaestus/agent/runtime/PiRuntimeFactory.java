package de.tum.cit.aet.hephaestus.agent.runtime;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.NetworkPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared Pi-agent kernel. Builds the sandbox-level scaffolding (settings, provider spec, env,
 * workspace inputs, base command, classpath resources, network policy) that every Pi-based agent
 * reuses.
 *
 * <p>Stays domain-agnostic: callers supply a {@link PiPlanSpec}. Nothing here knows about practices
 * or chat sessions.
 *
 * <p>SECURITY: a sandbox reaches an LLM only through the in-app proxy at
 * {@code $LLM_PROXY_URL}/{@code $LLM_PROXY_TOKEN}. The real provider API key never enters the
 * container, so nothing written here may carry one.
 */
@Component
public class PiRuntimeFactory {

    private static final Logger log = LoggerFactory.getLogger(PiRuntimeFactory.class);

    /** Time reserved for runner shutdown and sandbox teardown, outside the model's work budget. */
    public static final int TIMEOUT_BUFFER_SECONDS = 60;

    /** Turns the SDK repeats before a provider failure ends the session that hit it. */
    private static final int RETRY_MAX_ATTEMPTS = 5;

    /** Wait before the first repeat; each further attempt doubles it. */
    private static final int RETRY_BASE_DELAY_MS = 4000;

    static final String AGENT_RESOURCE_PREFIX = "agent/";

    private final ObjectMapper objectMapper;

    public PiRuntimeFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Build a Pi sandbox plan ready for the executor. */
    public PiPlan build(PiPlanSpec spec) {
        Map<String, String> env = new HashMap<>();
        Map<String, byte[]> inputFiles = new LinkedHashMap<>();

        inputFiles.put(SandboxLayout.PI_AGENT_PREFIX + "settings.json", buildPiSettingsJson(spec.upstreamModelId()));
        inputFiles.put(SandboxLayout.PROVIDER_CONFIG_FILENAME, buildProviderConfigJson(spec));
        inputFiles.put(
                SandboxLayout.NODE_PACKAGE_JSON_FILENAME,
                "{\"type\":\"module\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Digested as the run's prompt version, so settings.json and pi-provider.json stay out: they
        // vary by model, which the job's config snapshot already pins.
        Map<String, byte[]> promptScaffolding = new LinkedHashMap<>();
        promptScaffolding.put(SandboxLayout.ORCHESTRATOR_PATH, loadClasspathResource("pi-orchestrator.md"));
        promptScaffolding.put(
                SandboxLayout.RUNNER_SCRIPT_FILENAME,
                loadClasspathResource(spec.runnerProfile().runnerScript()));
        for (String sidecar : spec.runnerProfile().sidecarScripts()) {
            promptScaffolding.put(sidecar, loadClasspathResource(sidecar));
        }
        for (String prompt : spec.runnerProfile().promptResources()) {
            promptScaffolding.put(prompt, loadClasspathResource(prompt));
        }
        String promptDigest = ProvenanceDigest.rootDigestHex(promptScaffolding);
        inputFiles.putAll(promptScaffolding);
        inputFiles.putAll(spec.extraInputs());

        long agentTimeoutMs = (spec.timeoutSeconds() - TIMEOUT_BUFFER_SECONDS) * 1000L;
        env.put("AGENT_BUDGET_MS", Long.toString(agentTimeoutMs));

        env.put("HOME", "/home/agent");
        env.put("XDG_CONFIG_HOME", "/home/agent/.local/config");
        env.put("TMPDIR", PiRunnerProfile.AGENT_TMPDIR);
        env.put("PI_CODING_AGENT_DIR", SandboxLayout.PI_AGENT_DIR);

        String workspaceRoot = SandboxLayout.WORKSPACE_ROOT;
        PiRunnerProfile profile = spec.runnerProfile();
        String runtimeFlagsFragment = renderRuntimeFlags(profile.runtimeFlags());
        String runtimeEnvFragment = renderRuntimeEnv(profile.additionalEnv());

        String precomputeStep = spec.precomputeStep();
        String jobToken = spec.jobToken();
        if (precomputeStep == null || jobToken == null) {
            throw new IllegalStateException("validated Pi plan contains null fields");
        }
        // Every directory the runner may write exists before Node starts; PiRunnerProfile says why.
        String command = "mkdir -p " + String.join(" ", PiRunnerProfile.WRITABLE_DIRECTORIES)
                + " /home/agent/.local/config && "
                + precomputeStep
                + runtimeEnvFragment
                + "node "
                + runtimeFlagsFragment
                + workspaceRoot
                + "/"
                + SandboxLayout.RUNNER_SCRIPT_FILENAME;

        NetworkPolicy networkPolicy = buildNetworkPolicy(jobToken, spec.allowInternet());

        log.debug(
                "Built Pi plan: timeout={}s, apiProtocol={}, model={}, files={}",
                spec.timeoutSeconds(),
                spec.apiProtocol(),
                spec.upstreamModelId(),
                inputFiles.size());
        return new PiPlan(
                List.of("sh", "-c", command), Map.copyOf(env), Map.copyOf(inputFiles), networkPolicy, promptDigest);
    }

    private static String renderRuntimeFlags(List<String> flags) {
        if (flags == null || flags.isEmpty()) {
            return "";
        }
        return String.join(" ", flags) + " ";
    }

    /** Emitted verbatim into the command line, unquoted: a profile's values must be shell-safe. */
    private static String renderRuntimeEnv(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : env.entrySet()) {
            b.append(e.getKey()).append('=').append(e.getValue()).append(' ');
        }
        return b.toString();
    }

    /**
     * The settings JSON Pi loads at session start. {@code defaultModel} is the upstream model id
     * verbatim, because Pi looks it up by exact match.
     */
    public byte[] buildPiSettingsJson(@org.jspecify.annotations.Nullable String upstreamModelId) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("defaultProvider", "hephaestus");
        if (upstreamModelId != null && !upstreamModelId.isBlank()) {
            settings.put("defaultModel", upstreamModelId);
        }
        settings.put("transport", "sse");
        Map<String, Object> compaction = new LinkedHashMap<>();
        compaction.put("enabled", true);
        compaction.put("reserveTokens", 16384);
        settings.put("compaction", compaction);
        // A provider that answers "overloaded" is answered by waiting. The SDK's own default gives up
        // after 2+4+8 seconds, which ended whole practice lanes here while the review still had most of
        // its budget left, and a lane that dies takes its practice out of the review's coverage. Five
        // attempts four seconds apart, doubling, ride out just over two minutes of provider trouble,
        // and a review that spends that on every turn still ends inside its own watchdog.
        Map<String, Object> retry = new LinkedHashMap<>();
        retry.put("enabled", true);
        retry.put("maxRetries", RETRY_MAX_ATTEMPTS);
        retry.put("baseDelayMs", RETRY_BASE_DELAY_MS);
        settings.put("retry", retry);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(settings);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize Pi settings", e);
        }
    }

    /**
     * The non-secret provider spec the runners read to register the {@code hephaestus} Pi provider.
     * No {@code baseUrl}: the sandbox adapter only resolves {@code $LLM_PROXY_URL} at container start.
     */
    byte[] buildProviderConfigJson(PiPlanSpec spec) {
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("apiProtocol", spec.apiProtocol());
        provider.put("modelId", spec.upstreamModelId());
        provider.put("supportsReasoning", spec.supportsReasoning());
        if (spec.contextWindow() != null) {
            provider.put("contextWindow", spec.contextWindow());
        }
        if (spec.maxOutputTokens() != null) {
            provider.put("maxOutputTokens", spec.maxOutputTokens());
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(provider);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize pi-provider.json", e);
        }
    }

    /** The sandbox layer fills in {@code llmProxyUrl} during PREPARE; this only shapes the policy. */
    static NetworkPolicy buildNetworkPolicy(String jobToken, boolean allowInternet) {
        return new NetworkPolicy(allowInternet, null, jobToken);
    }

    /** Read a classpath resource under {@link #AGENT_RESOURCE_PREFIX}. */
    public static byte[] loadClasspathResource(String relativePath) {
        String fullPath = AGENT_RESOURCE_PREFIX + relativePath;
        try (InputStream is = PiRuntimeFactory.class.getClassLoader().getResourceAsStream(fullPath)) {
            if (is == null) {
                throw new IllegalStateException("Missing classpath resource: " + fullPath);
            }
            return is.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read classpath resource: " + fullPath, e);
        }
    }

    /**
     * @param promptDigest digest of the prompt scaffolding — the run's prompt version
     */
    public record PiPlan(
            List<String> command,
            Map<String, String> environment,
            Map<String, byte[]> inputFiles,
            NetworkPolicy networkPolicy,
            String promptDigest) {
        public PiPlan {
            command = List.copyOf(Objects.requireNonNull(command, "command"));
            environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
            inputFiles = Map.copyOf(Objects.requireNonNull(inputFiles, "inputFiles"));
            Objects.requireNonNull(networkPolicy, "networkPolicy");
            Objects.requireNonNull(promptDigest, "promptDigest");
        }
    }
}
