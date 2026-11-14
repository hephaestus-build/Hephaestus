package de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest;

import de.tum.cit.aet.hephaestus.agent.conversation.turn.TurnRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunHandler;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunStopReason;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Proactive conversation work has the same fenced turn lifecycle and retry policy as an answer. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class DigestRunKind implements AgentRunHandler {
    private final TurnRunKind turns;

    @Override
    public AgentRunKind kind() {
        return AgentRunKind.DIGEST;
    }

    @Override
    public int retries() {
        return turns.retries();
    }

    @Override
    public Duration retryAfter(int spentAttempts) {
        return turns.retryAfter(spentAttempts);
    }

    @Override
    public void lock(AgentRunRef run) {
        turns.lock(run);
    }

    @Override
    public LossOutcome lost(AgentRunFence fence) {
        return turns.lost(fence);
    }

    @Override
    public void ended(AgentRunRef run, int lastEpoch, AgentRunStopReason reason) {
        turns.ended(run, lastEpoch, reason);
    }
}
