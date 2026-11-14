package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarter;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.TurnRunsAdapter;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAttempt;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorTurnAccess;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A conversation turn in its conversation's sandbox (ADR 0079 § Interactive): the sandbox's pull starts the turn's
 * attempt, its loop reads the turn's context and calls the turn routes ({@link HephTurnController}), and the turn's own
 * end ends its run ({@code MentorTurnService.end}). The run's deadline bounds the attempt: its work deadline and the
 * grace to end what it wrote ({@link TurnRunsAdapter#GRACE}).
 */
@Component
@ConditionalOnAgentRuntime
@Profile(HephTurnConfiguration.PROFILES)
@WorkspaceAgnostic("Each call names a run of the sandbox's own workspace and subject")
public class TurnSandboxRunKind implements SandboxRunKind {

    private final MentorTurnStarter starter;
    private final TurnRunsAdapter turnRuns;
    private final AgentRuns runs;
    private final HephTurns turns;
    private final HephTurnContexts contexts;
    private final MentorTurnAccess rows;
    private final MentorTurnService turnService;
    private final MentorTurnCompletion completion;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final Counter claimFailures;

    public TurnSandboxRunKind(
            MentorTurnStarter starter,
            TurnRunsAdapter turnRuns,
            AgentRuns runs,
            HephTurns turns,
            HephTurnContexts contexts,
            MentorTurnAccess rows,
            MentorTurnService turnService,
            MentorTurnCompletion completion,
            Clock clock,
            ObjectMapper mapper,
            MeterRegistry meters) {
        this.starter = starter;
        this.turnRuns = turnRuns;
        this.runs = runs;
        this.turns = turns;
        this.contexts = contexts;
        this.rows = rows;
        this.turnService = turnService;
        this.completion = completion;
        this.clock = clock;
        this.mapper = mapper;
        this.claimFailures = Counter.builder(AgentMetrics.MENTOR_TURN_CLAIM_FAILURE)
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
    public boolean claimed(SandboxRun run) {
        AgentRunFence claim = run.fence();
        Optional<MentorTurnStarter.StartedTurn> started;
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
        MentorTurn turn = started.get().turn();
        Instant workDeadline = clock.instant().plusSeconds(started.get().llm().timeoutSeconds());
        turnRuns.limit(claim.workspaceId(), turn.getId(), claim.runId(), claim.epoch(), workDeadline);
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
        return turns.write(run, false, turn -> mapper.valueToTree(contexts.context(turn)));
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
        Optional<MentorTurn> held = turnService
                .lockByRun(claim.workspaceId(), claim.runId())
                .filter(turn -> turn.getAttempt() == claim.epoch());
        if (held.isEmpty()) return false;
        MentorTurn turn = held.get();
        if (turn.getStatus() == MentorTurn.Status.QUEUED) return false;
        if (turn.getStatus() == MentorTurn.Status.DONE || turn.getStatus() == MentorTurn.Status.FAILED)
            return runs.end(claim, TurnRunsAdapter.endReason(turn.getStatus())) != AgentRuns.EndOutcome.STALE;
        if (!runs.isCurrent(claim.run(), claim.epoch()))
            return turn.getStatus() == MentorTurn.Status.STOPPED
                    && runs.end(claim, TurnRunsAdapter.endReason(turn.getStatus())) != AgentRuns.EndOutcome.STALE;
        if (outcome == Outcome.HANDED_ON) {
            MentorTurnService.LostAttempt taken = turnService.takeBack(turn, true);
            completion.billLostAttempt(taken);
            return turn.getStatus() == MentorTurn.Status.QUEUED
                    ? runs.requeue(claim, AgentRuns.Charge.SPEND, Duration.ZERO, null)
                    : runs.end(claim, TurnRunsAdapter.endReason(turn.getStatus())) != AgentRuns.EndOutcome.STALE;
        }
        completion.end(
                claim.workspaceId(),
                turn.getId(),
                claim.epoch(),
                claim.runId(),
                new MentorTurnCompletion.Ending(
                        MentorTurn.Status.FAILED,
                        List.of(),
                        HephTurns.COULD_NOT_FINISH,
                        "its sandbox ended the run without the turn",
                        null,
                        null));
        return true;
    }
}
