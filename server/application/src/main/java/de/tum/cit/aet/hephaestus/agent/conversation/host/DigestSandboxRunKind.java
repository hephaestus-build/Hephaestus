package de.tum.cit.aet.hephaestus.agent.conversation.host;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAttempt;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** A system digest uses the conversation's existing runner, claim, context and end routes. */
@Component
@ConditionalOnAgentRuntime
@Profile(ConversationTurnConfiguration.PROFILES)
@RequiredArgsConstructor
public class DigestSandboxRunKind implements SandboxRunKind {
    private final TurnSandboxRunKind turns;

    @Override
    public AgentRunKind kind() {
        return AgentRunKind.DIGEST;
    }

    @Override
    public AgentSandboxKind sandbox() {
        return AgentSandboxKind.CONVERSATION;
    }

    @Override
    public void beforeWrite(AgentSandboxes.LiveSandbox sandbox) {
        turns.beforeWrite(sandbox);
    }

    @Override
    public boolean claimed(SandboxRun run) {
        return turns.claimed(run);
    }

    @Override
    public Optional<ProxyAttempt> attempt(SandboxRun run) {
        return turns.attempt(run);
    }

    @Override
    public JsonNode context(SandboxRun run) {
        return turns.context(run);
    }

    @Override
    public boolean end(SandboxRun run, Outcome outcome) {
        return turns.end(run, outcome);
    }
}
