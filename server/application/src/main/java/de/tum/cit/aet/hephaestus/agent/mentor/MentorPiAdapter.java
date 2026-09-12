package de.tum.cit.aet.hephaestus.agent.mentor;

import de.tum.cit.aet.hephaestus.agent.config.AgentBindingLimits;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.MentorContextKeys;
import de.tum.cit.aet.hephaestus.agent.proxy.MentorProxyCredentialRegistry;
import de.tum.cit.aet.hephaestus.agent.proxy.MentorProxyCredentialRegistry.Route;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.PiPlanSpec;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory.PiPlan;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SecurityProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Mentor adapter: builds an {@link InteractiveSandboxSpec} for a long-lived stdin/stdout JSONL
 * session, symmetric to {@code PracticePiAdapter}'s one-shot {@code task.json} build.
 * Single-flight is enforced by the sandbox registry's {@code (userId, workspaceId)} keying, where the
 * mentee's {@code developerId} is carried in the spec's {@code userId} slot.
 */
@Service
@RequiredArgsConstructor
public class MentorPiAdapter {

    public static final String SYSTEM_PROMPT_PATH = SandboxLayout.MENTOR_SYSTEM_PROMPT_PATH;
    public static final String CONTEXT_INPUT_PREFIX = SandboxLayout.CONTEXT_PREFIX;
    public static final String SESSIONS_DIR_PREFIX = SandboxLayout.SESSIONS_DIR_PREFIX;

    private static final MentorRunnerProfile PROFILE = new MentorRunnerProfile();

    private final PiRuntimeFactory runtimeFactory;
    private final AgentImageProperties imageProperties;
    private final MentorProxyCredentialRegistry proxyCredentialRegistry;

    /**
     * Build the interactive sandbox spec for a mentor chat session. A non-null {@code sessionRestore}
     * injects the prior turn's JSONL so Pi restores it, keeping the prompt cache warm.
     */
    public InteractiveSandboxSpec buildSandboxSpec(
            MentorAgentRequest request,
            MentorLlmConfig llmConfig,
            Map<String, byte[]> contextInputs,
            @Nullable SessionRestore sessionRestore) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(llmConfig, "llmConfig");
        Objects.requireNonNull(contextInputs, "contextInputs");
        validateContextInputs(contextInputs);

        Map<String, byte[]> extraInputs = new LinkedHashMap<>(contextInputs);
        extraInputs.put(SYSTEM_PROMPT_PATH, PiRuntimeFactory.loadClasspathResource("mentor/system.md"));
        if (sessionRestore != null) {
            extraInputs.put(SESSIONS_DIR_PREFIX + sessionRestore.threadId() + ".jsonl", sessionRestore.bytes());
        }

        String baseUrl = llmConfig.baseUrl();

        int timeoutSeconds = clampToRunnableTurnBudget(llmConfig.timeoutSeconds());

        // Generated here rather than inside InteractiveSandboxSpec so it can also key the mint: the
        // sandbox adapter revokes this token by the same sessionId when it disposes the session.
        UUID sessionId = UUID.randomUUID();
        String proxyToken = proxyCredentialRegistry.mint(
                sessionId,
                new Route(
                        llmConfig.apiProtocol(),
                        baseUrl,
                        llmConfig.connectionScope(),
                        llmConfig.connectionId(),
                        llmConfig.modelId(),
                        llmConfig.workspaceId()));

        PiPlanSpec planSpec = new PiPlanSpec(
                llmConfig.apiProtocol(),
                llmConfig.upstreamModelId(),
                llmConfig.contextWindow(),
                llmConfig.maxOutputTokens(),
                llmConfig.supportsReasoning(),
                proxyToken,
                llmConfig.allowInternet(),
                timeoutSeconds,
                PROFILE,
                extraInputs,
                "");

        PiPlan plan = runtimeFactory.build(planSpec);

        return new InteractiveSandboxSpec(
                sessionId,
                Long.toString(request.developerId()),
                Long.toString(request.workspaceId()),
                imageProperties.reference(),
                plan.command(),
                plan.environment(),
                plan.networkPolicy(),
                ResourceLimits.DEFAULT,
                SecurityProfile.DEFAULT,
                plan.inputFiles());
    }

    /**
     * The binding API's floor sits below the runtime's, and a binding that never went through that API
     * could exceed its ceiling — so clamp both ends here, where a turn's budget is actually fixed.
     */
    private static int clampToRunnableTurnBudget(int configuredTimeoutSeconds) {
        return Math.clamp(
                configuredTimeoutSeconds,
                PiRuntimeFactory.TIMEOUT_BUFFER_SECONDS + 1,
                AgentBindingLimits.MAX_TIMEOUT_SECONDS);
    }

    private static void validateContextInputs(Map<String, byte[]> contextInputs) {
        for (Map.Entry<String, byte[]> entry : contextInputs.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(CONTEXT_INPUT_PREFIX)) {
                throw new IllegalArgumentException(
                        "contextInputs key must begin with '" + CONTEXT_INPUT_PREFIX + "', got: " + key);
            }
            if (!MentorContextKeys.ALLOWED_OUTPUT_KEYS.contains(key)) {
                throw new IllegalArgumentException("unsupported mentor context input key: " + key);
            }
            // Checked here so the failure names the key, not as an NPE deep inside PiPlanSpec.
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("contextInputs value for '" + key + "' must not be null");
            }
        }
    }
}
