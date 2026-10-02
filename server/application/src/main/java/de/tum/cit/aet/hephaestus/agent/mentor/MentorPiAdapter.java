package de.tum.cit.aet.hephaestus.agent.mentor;

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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Mentor adapter: builds an {@link InteractiveSandboxSpec} for a long-lived stdin/stdout JSONL
 * session, symmetric to {@code PracticePiAdapter}'s one-shot {@code task.json} build.
 * Single-flight is enforced by the sandbox registry's {@code (userId, workspaceId)} keying, where the
 * mentee's {@code developerId} is carried in the spec's {@code userId} slot.
 *
 * <p>The spec carries nothing of a turn or a thread, so a sandbox prepared before the first message is
 * the one that message runs in: the runner reads context through {@code fetch_context}, and a
 * thread's saved session arrives with {@code open_thread}.
 */
@Service
@RequiredArgsConstructor
public class MentorPiAdapter {

    public static final String SYSTEM_PROMPT_PATH = SandboxLayout.MENTOR_SYSTEM_PROMPT_PATH;

    private static final MentorRunnerProfile PROFILE = new MentorRunnerProfile();

    private final PiRuntimeFactory runtimeFactory;
    private final AgentImageProperties imageProperties;
    private final MentorProxyCredentialRegistry proxyCredentialRegistry;

    /** Build the interactive sandbox spec for one developer's mentor sandbox in one workspace. */
    public InteractiveSandboxSpec buildSandboxSpec(MentorAgentRequest request, MentorLlmConfig llmConfig) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(llmConfig, "llmConfig");

        Map<String, byte[]> extraInputs =
                Map.of(SYSTEM_PROMPT_PATH, PiRuntimeFactory.loadClasspathResource("mentor/system.md"));

        String baseUrl = llmConfig.baseUrl();

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
                llmConfig.reasoningEffort(),
                proxyToken,
                llmConfig.allowInternet(),
                llmConfig.timeoutSeconds(),
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
}
