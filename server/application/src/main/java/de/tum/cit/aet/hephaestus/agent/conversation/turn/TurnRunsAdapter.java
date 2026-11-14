package de.tum.cit.aet.hephaestus.agent.conversation.turn;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunStopReason;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.conversation.ConversationLeaseLostException;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.spi.TurnRuns;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A turn's run ({@link TurnRuns}): one run per turn, keyed to its conversation, in the interactive band. One run of a
 * conversation is leased at a time, so a conversation answers its questions in order, and a digest of it waits too.
 */
@Component
@RequiredArgsConstructor
public class TurnRunsAdapter implements TurnRuns {

    /** How long a stopped turn's holder, or one past its work deadline, may still end the turn with what it wrote. */
    public static final Duration GRACE = Duration.ofSeconds(30);

    /** How long a turn waits for a free Hephaestus once nothing of its conversation is ahead of it. */
    public static final Duration START_WAIT = Duration.ofMinutes(3);

    private final AgentRuns runs;
    private final Clock clock;

    /** The subject of a conversation's runs: also the subject of its sandbox. */
    public static String subject(UUID conversationId) {
        return "conversation:" + conversationId;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(long workspaceId, UUID conversationId) {
        return runs.enqueue(
                        workspaceId, AgentRunKind.TURN, AgentRunBand.INTERACTIVE, subject(conversationId), START_WAIT)
                .id();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueueDigest(long workspaceId, UUID conversationId, Duration startWithin) {
        return runs.enqueue(
                        workspaceId, AgentRunKind.DIGEST, AgentRunBand.BACKGROUND, subject(conversationId), startWithin)
                .id();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCurrent(long workspaceId, UUID turnId, UUID runId, int epoch) {
        try {
            runs.requireCurrent(new AgentRunFence(runId, workspaceId, epoch));
        } catch (StaleRunException stale) {
            ConversationLeaseLostException lost = new ConversationLeaseLostException(turnId, epoch);
            lost.initCause(stale);
            throw lost;
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void ended(long workspaceId, UUID runId, int epoch, ConversationTurn.Status outcome) {
        if (runs.end(new AgentRunFence(runId, workspaceId, epoch), endReason(outcome)) == AgentRuns.EndOutcome.STALE)
            throw new IllegalStateException("The turn's run ended under another claim while the turn was held");
    }

    /** The one mapping for a turn holder's terminal outcome, shared with the sandbox's repeated end. */
    public static AgentRunStopReason endReason(ConversationTurn.Status status) {
        return switch (status) {
            case DONE -> AgentRunStopReason.COMPLETED;
            case FAILED -> AgentRunStopReason.FAILED;
            case STOPPED -> AgentRunStopReason.CANCELLED;
            case QUEUED, RUNNING -> throw new IllegalArgumentException("An unfinished turn cannot end its run");
        };
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void stopped(long workspaceId, UUID runId, boolean wasRunning) {
        AgentRunRef run = new AgentRunRef(runId, workspaceId);
        if (wasRunning) runs.endBy(run, clock.instant().plus(GRACE));
        else runs.cancel(run);
    }

    /**
     * Bounds the claim that starts a turn's attempt: its run ends {@link #GRACE} after {@code workDeadline}, so its
     * holder can still end what it wrote, and the run reaper ends it then.
     */
    @Transactional
    public void limit(long workspaceId, UUID turnId, UUID runId, int epoch, Instant workDeadline) {
        if (!runs.limit(new AgentRunFence(runId, workspaceId, epoch), workDeadline.plus(GRACE)))
            throw new ConversationLeaseLostException(turnId, epoch);
    }

    /** Renews the attempt's claim of its turn's run; false once it lost the run, or the run passed its deadline. */
    @Transactional
    public boolean renew(long workspaceId, UUID runId, int epoch) {
        return runs.renew(new AgentRunFence(runId, workspaceId, epoch)).isPresent();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void moveQueued(long workspaceId, UUID fromConversationId, UUID toConversationId) {
        runs.moveQueued(workspaceId, AgentRunKind.TURN, subject(fromConversationId), subject(toConversationId));
        runs.moveQueued(workspaceId, AgentRunKind.DIGEST, subject(fromConversationId), subject(toConversationId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requeue(long workspaceId, UUID turnId, UUID runId, int epoch) {
        if (!runs.requeue(new AgentRunFence(runId, workspaceId, epoch), AgentRuns.Charge.FREE, Duration.ZERO, null))
            throw new ConversationLeaseLostException(turnId, epoch);
    }
}
