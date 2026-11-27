package de.tum.cit.aet.hephaestus.agent.conversation.host;

import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnStarter;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.TurnRunsAdapter;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAttempt;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunLease;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.conversation.ConversationSituation;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationTurnAccess;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A conversation turn in its conversation's sandbox (ADR 0079 § Interactive): the sandbox's pull starts the turn's
 * attempt, its loop reads the turn's context and calls the turn routes ({@link ConversationTurnController}), and the turn's own
 * end ends its run ({@code ConversationTurnService.end}). The run's deadline bounds the attempt: its work deadline and the
 * grace to end what it wrote ({@link TurnRunsAdapter#GRACE}).
 */
@Component
@ConditionalOnAgentRuntime
@Profile(ConversationTurnConfiguration.PROFILES)
public class TurnSandboxRunKind implements SandboxRunKind {

    private final ConversationTurnStarter starter;
    private final TurnRunsAdapter turnRuns;
    private final AgentRuns runs;
    private final ConversationTurns turns;
    private final ConversationTurnContexts contexts;
    private final ConversationSituation situations;
    private final ConversationTurnAccess rows;
    private final ConversationTurnService turnService;
    private final ConversationTurnCompletion completion;
    private final ObjectMapper mapper;
    private final Counter claimFailures;

    public TurnSandboxRunKind(
            ConversationTurnStarter starter,
            TurnRunsAdapter turnRuns,
            AgentRuns runs,
            ConversationTurns turns,
            ConversationTurnContexts contexts,
            ConversationSituation situations,
            ConversationTurnAccess rows,
            ConversationTurnService turnService,
            ConversationTurnCompletion completion,
            ObjectMapper mapper,
            MeterRegistry meters) {
        this.starter = starter;
        this.turnRuns = turnRuns;
        this.runs = runs;
        this.turns = turns;
        this.contexts = contexts;
        this.situations = situations;
        this.rows = rows;
        this.turnService = turnService;
        this.completion = completion;
        this.mapper = mapper;
        this.claimFailures = Counter.builder(AgentMetrics.CONVERSATION_TURN_CLAIM_FAILURE)
                .description("Conversation turns the gateway could not start or admit")
                .register(meters);
    }

    @Override
    public AgentRunKind kind() {
        return AgentRunKind.TURN;
    }

    @Override
    public AgentSandboxKind sandbox() {
        return AgentSandboxKind.CONVERSATION;
    }

    @Override
    public void beforeWrite(AgentSandboxes.LiveSandbox sandbox) {
        turns.beforeWrite(sandbox);
    }

    /**
     * Starts and admits the turn's attempt, and ends its run a grace after the attempt's work deadline. A turn that no
     * longer waits takes its run with it; admission that declines a turn ends both.
     */
    @Override
    public boolean claimed(AgentSandboxes.LiveSandbox sandbox, AgentRunLease lease) {
        AgentRunFence claim = lease.fence();
        Optional<ConversationTurnStarter.StartedTurn> started;
        try {
            started = starter.startClaimed(claim);
        } catch (RuntimeException failed) {
            claimFailures.increment();
            throw failed;
        }
        if (started.isEmpty()) {
            runs.cancel(claim.run());
            return false;
        }
        ConversationTurn turn = started.get().turn();
        turnRuns.setWorkDeadline(lease, turn.getId(), started.get().llm().timeoutSeconds());
        return true;
    }

    @Override
    public Optional<ProxyAttempt> attempt(SandboxRun run) {
        AgentRunFence claim = run.fence();
        return rows.runAttempt(claim.workspaceId(), claim.runId(), claim.epoch())
                .map(held -> new ProxyAttempt(
                        ProxyAttempt.Caller.TURN, held.id(), claim.workspaceId(), claim.epoch(), claim.runId(), null));
    }

    /** The turn's system prompt, path, situation and model, as {@code GET …/context} answers it. */
    @Override
    public JsonNode context(SandboxRun run) {
        var prepared = turns.read(
                run,
                contexts::context,
                (turn, context) -> situations.recordInputs(turn.workspaceId(), turn.turnId(), context.quotedEntries()));
        return mapper.valueToTree(prepared.context());
    }

    /**
     * The driver ends the run after the turn's own end ended both, so the run usually ended already. A run whose turn
     * still runs was ended without the turn, which only a call outside the loop makes: the turn fails with it. A
     * hand-on, as a sandbox whose network policy did not hold sends, gives the turn back to the queue, as a lost holder
     * does.
     */
    @Override
    public boolean end(SandboxRun run, Outcome outcome) {
        AgentRunFence claim = run.fence();
        Optional<ConversationTurn> held = turnService
                .lockByRun(claim.workspaceId(), claim.runId())
                .filter(turn -> turn.getAttempt() == claim.epoch());
        if (held.isEmpty()) return false;
        ConversationTurn turn = held.get();
        var turnStatus = turn.getStatus();
        if (turnStatus == ConversationTurn.Status.QUEUED) return false;
        if (turnStatus == ConversationTurn.Status.DONE || turnStatus == ConversationTurn.Status.FAILED)
            return runs.end(claim, TurnRunsAdapter.endReason(turnStatus)) != AgentRuns.EndOutcome.STALE;
        boolean handingOn = outcome == Outcome.HANDED_ON;
        boolean current = (handingOn && turnStatus == ConversationTurn.Status.RUNNING)
                ? runs.lockConversationHandoff(claim)
                : runs.isCurrent(claim.run(), claim.epoch());
        if (!current)
            return turnStatus == ConversationTurn.Status.STOPPED
                    && runs.end(claim, TurnRunsAdapter.endReason(turnStatus)) != AgentRuns.EndOutcome.STALE;
        if (handingOn) {
            ConversationTurnService.LostAttempt taken = turnService.takeBack(turn, true);
            completion.billLostAttempt(taken);
            return turn.getStatus() == ConversationTurn.Status.QUEUED
                    ? runs.requeue(claim, AgentRuns.Charge.SPEND, Duration.ZERO, null)
                    : runs.end(claim, TurnRunsAdapter.endReason(turn.getStatus())) != AgentRuns.EndOutcome.STALE;
        }
        completion.end(
                claim.workspaceId(),
                turn.getId(),
                claim.epoch(),
                claim.runId(),
                new ConversationTurnCompletion.Ending(
                        ConversationTurn.Status.FAILED,
                        List.of(),
                        ConversationTurns.COULD_NOT_FINISH,
                        "its sandbox ended the run without the turn",
                        null,
                        null));
        return true;
    }
}
