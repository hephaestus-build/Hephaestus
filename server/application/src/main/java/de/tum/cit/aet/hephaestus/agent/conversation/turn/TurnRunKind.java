package de.tum.cit.aet.hephaestus.agent.conversation.turn;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunHandler;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunStopReason;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnProgress;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Conversation turns on runs ({@link TurnRunsAdapter}): what becomes of a turn whose run's holder is gone. The reaper
 * calls it in the transaction that takes the run back, after it locked the turn, so the turn moves with its run.
 *
 * <ul>
 *   <li>A lost holder: a running turn returns to the queue for its one retry, and a stopped turn stays stopped. The
 *       lost attempt is billed; it stored nothing.
 *   <li>The run's deadline: the holder of a stopped turn, or of one past its work deadline, did not end it within
 *       {@link TurnRunsAdapter#GRACE}. The server ends it: stopped, or failed for taking too long. Only the holder has
 *       the entries, so the partial answer readers saw last settles the feedback it called.
 *   <li>The run's start window: the turn waited longer than {@link TurnRunsAdapter#START_WAIT} for a free Hephaestus.
 * </ul>
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Each call names a run, which carries its workspace")
public class TurnRunKind implements AgentRunHandler {

    private static final Logger log = LoggerFactory.getLogger(TurnRunKind.class);

    private final ConversationTurnService turns;
    private final ConversationTurnCompletion completion;
    private final ConversationTurnProgress progress;
    private final MeterRegistry meters;

    @Override
    public AgentRunKind kind() {
        return AgentRunKind.TURN;
    }

    /** One automatic retry after a lost holder. */
    @Override
    public int retries() {
        return 1;
    }

    /** A person waits for the answer, so a lost turn is offered again at once. */
    @Override
    public Duration retryAfter(int spentAttempts) {
        return Duration.ZERO;
    }

    @Override
    public void lock(AgentRunRef run) {
        turns.lockByRun(run.workspaceId(), run.id());
    }

    @Override
    public LossOutcome lost(AgentRunFence lost) {
        ConversationTurn turn = turns.lockByRun(lost.workspaceId(), lost.runId())
                .filter(found -> found.getAttempt() == lost.epoch())
                .orElse(null);
        if (turn == null) return new End();
        ConversationTurnService.LostAttempt taken = turns.takeBack(turn, true);
        completion.billLostAttempt(taken);
        log.info(
                "Conversation turn lost its holder: turnId={}, attempt={}, now={}",
                turn.getId(),
                taken.attempt(),
                turn.getStatus());
        return turn.getStatus() == ConversationTurn.Status.QUEUED ? new Requeue() : new End();
    }

    @Override
    public void ended(AgentRunRef run, int lastEpoch, AgentRunStopReason reason) {
        ConversationTurn turn = turns.lockByRun(run.workspaceId(), run.id()).orElse(null);
        if (turn == null) return;
        switch (reason) {
            case NOT_STARTED -> {
                if (turn.getStatus() != ConversationTurn.Status.QUEUED) return;
                turns.expireQueued(turn, ConversationTurnCompletion.COULD_NOT_START);
                if (turn.getSurface() != ConversationTurn.Surface.SYSTEM) {
                    meters.counter(AgentMetrics.CONVERSATION_TURN_COMPLETED, "outcome", "failed")
                            .increment();
                }
                log.warn("Conversation turn {} could not start before its start wait ran out", turn.getId());
            }
            case DEADLINE -> {
                if (turn.getAttempt() != lastEpoch
                        || (turn.getStatus() != ConversationTurn.Status.RUNNING
                                && turn.getStatus() != ConversationTurn.Status.STOPPED)) return;
                boolean stopped = turn.getStatus() == ConversationTurn.Status.STOPPED;
                completion.endWithoutHolder(
                        turn,
                        new ConversationTurnCompletion.Ending(
                                stopped ? ConversationTurn.Status.STOPPED : ConversationTurn.Status.FAILED,
                                List.of(),
                                stopped ? null : ConversationTurnCompletion.TOOK_TOO_LONG,
                                stopped
                                        ? null
                                        : "its holder did not end it within " + TurnRunsAdapter.GRACE
                                                + " after its deadline",
                                null,
                                progress.snapshot(turn.getWorkspaceId(), turn.getId(), turn.getAttempt())));
            }
            default -> {
                // The lost holder spent the turn's last retry.
                if (turn.getStatus() != ConversationTurn.Status.RUNNING
                        && turn.getStatus() != ConversationTurn.Status.STOPPED) return;
                completion.billLostAttempt(turns.takeBack(turn, false));
            }
        }
    }
}
