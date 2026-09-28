package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorAgentRequest;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorLlmConfig;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorPiAdapter;
import de.tum.cit.aet.hephaestus.agent.proxy.MentorProxyCredentialRegistry;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxService;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

/**
 * Starts a developer's Heph sandbox before their first message, so the turn that follows finds it warm. It builds
 * the same spec a turn builds and attaches it, and sends no prompt, calls no model and writes no thread.
 */
@Service
@NamedInterface(name = "mentor-chat")
@RequiredArgsConstructor
public class MentorSandboxPreparer {

    private static final Logger log = LoggerFactory.getLogger(MentorSandboxPreparer.class);

    // Only a runtime with the worker capability can attach a live sandbox.
    private final ObjectProvider<InteractiveSandboxService> interactiveSandboxServiceProvider;
    private final MentorTurnLock turnLock;
    private final MemberAiRoutingAdapter memberAiRouting;
    private final LlmAdmissionService llmAdmissionService;
    private final LlmBudgetService llmBudgetService;
    private final MentorPiAdapter mentorPiAdapter;
    private final MentorProxyCredentialRegistry proxyCredentialRegistry;
    private final MentorChatExecutorConfig.MentorTurnExecutor turnExecutor;

    /**
     * Returns at once; the sandbox starts on the turn executor. Skipped where a turn could not run or would not
     * need it: no sandbox service in this runtime, a turn holding the sandbox, a warm sandbox, no Heph model for
     * the developer, a spent budget, or the session caps. The caller verifies the developer's membership.
     */
    public void prepare(long workspaceId, long developerId) {
        InteractiveSandboxService sandboxService = interactiveSandboxServiceProvider.getIfAvailable();
        if (sandboxService == null) {
            return;
        }
        try {
            turnExecutor.executor().execute(() -> prepareSandbox(sandboxService, workspaceId, developerId));
        } catch (RejectedExecutionException rejected) {
            log.debug("Mentor sandbox prepare rejected by executor: {}", rejected.getMessage());
        }
    }

    // Closing the scope is the operation; its binding is intentionally unread.
    @SuppressWarnings("try")
    private void prepareSandbox(InteractiveSandboxService sandboxService, long workspaceId, long developerId) {
        // tryLock, never lock: a turn that holds the sandbox attaches for itself, and a prepare must not queue it.
        var lock = turnLock.tryAcquireSandboxLock(new MentorTurnLock.SandboxKey(workspaceId, developerId));
        if (lock.isEmpty()) {
            return;
        }
        try (var ignored = lock.get()) {
            Optional<WorkspaceAgentBinding> binding =
                    memberAiRouting.binding(workspaceId, AgentPurpose.MENTOR, developerId);
            if (binding.isEmpty()) {
                return;
            }
            MentorLlmConfig llmConfig =
                    MentorLlmConfig.fromAdmission(binding.get(), llmAdmissionService.admit(binding.get()));
            if (llmBudgetService.decide(workspaceId).blocks(llmConfig.connectionScope())) {
                return;
            }
            InteractiveSandboxSpec spec =
                    mentorPiAdapter.buildSandboxSpec(new MentorAgentRequest(workspaceId, developerId), llmConfig);
            boolean warm = true;
            try {
                warm = sandboxService.isWarm(spec);
            } finally {
                if (warm) {
                    // Only attach uses, and so revokes, the credential the spec minted.
                    proxyCredentialRegistry.revoke(spec.sessionId());
                }
            }
            if (!warm) {
                sandboxService.attach(spec);
                log.debug("Prepared mentor sandbox: workspaceId={}, developerId={}", workspaceId, developerId);
            }
        } catch (RuntimeException e) {
            // The next turn attaches for itself and reports whatever still fails.
            log.info(
                    "Mentor sandbox not prepared: workspaceId={}, developerId={}: {}",
                    workspaceId,
                    developerId,
                    e.getMessage());
        }
    }
}
