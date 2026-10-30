package de.tum.cit.aet.hephaestus.mentor;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tracks why a queued turn has not started and ends an eligible question past its start budget.
 * A turn waits for a free conversation slot when a worker may start it
 * ({@link MentorTurnRepository#CLAIMABLE}) and the turns that hold a Heph slot are at least
 * {@code hephaestus.mentor.turn.capacity}: every slot of the deployment is taken. Both facts are rows, so the reason
 * is as fresh as the read. Otherwise the reason is not known: the turn starts at once, waits for an earlier question
 * of its conversation, or no worker runs. Without a capacity, no turn has a reason.
 */
@Service
public class MentorTurnWaits {

    /** Each entry to the queue gets this wait, including the one automatic retry. */
    public static final Duration START_WAIT = Duration.ofMinutes(3);

    /**
     * The most queued turns that one change of the held slots publishes again, the newest first: a developer who just
     * asked is the likeliest to watch. An older turn past it, which only a long outage of every worker queues, shows
     * its new reason at its next read.
     */
    static final int MAX_PUBLISHED = 100;

    private final MentorTurnRepository turns;
    private final ApplicationEventPublisher events;
    private final @Nullable Integer capacity;

    /**
     * @param capacity the Heph turns that every worker together runs at once, or {@code null} when the server does not
     *     know it
     */
    public MentorTurnWaits(
            MentorTurnRepository turns,
            ApplicationEventPublisher events,
            @Value("${hephaestus.mentor.turn.capacity:#{null}}") @Nullable Integer capacity) {
        if (capacity != null && capacity < 1) {
            throw new IllegalArgumentException("hephaestus.mentor.turn.capacity must be at least 1, or unset");
        }
        this.turns = turns;
        this.events = events;
        this.capacity = capacity;
    }

    /**
     * A turn took a Heph slot, gave one back, or left its conversation's queue. Published inside the change; after the
     * commit, the turns whose reason it can change are published again.
     */
    public record SlotsChanged(long workspaceId) {}

    /** Why each queued turn among {@code shown} has not started; a turn whose reason is not known has no entry. */
    @Transactional(readOnly = true)
    public Map<UUID, MentorTurn.WaitingFor> waits(long workspaceId, Collection<MentorTurn> shown) {
        List<UUID> queued = shown.stream()
                .filter(turn -> turn.getStatus() == MentorTurn.Status.QUEUED)
                .map(MentorTurn::getId)
                .toList();
        if (capacity == null || queued.isEmpty() || turns.countHeld() < capacity) {
            return Map.of();
        }
        return turns.findClaimableIds(workspaceId, queued).stream()
                .collect(Collectors.toMap(Function.identity(), id -> MentorTurn.WaitingFor.FREE_HEPH));
    }

    /** Questions whose stored queue time is past the start budget; a worker restart cannot extend it. */
    @Transactional(readOnly = true)
    public List<UUID> overdueQueued(Instant now) {
        return turns.findOverdueQueued(now.minus(START_WAIT));
    }

    /** Ends an overdue question without claiming an attempt. A ready worker can win the row lock first. */
    @Transactional
    public boolean expireQueued(UUID turnId, Instant now, String error) {
        MentorTurn turn = turns.lockById(turnId).orElse(null);
        if (turn == null
                || turn.getStatus() != MentorTurn.Status.QUEUED
                || turn.getQueuedAt().plus(START_WAIT).isAfter(now)
                || !turns.findClaimableIds(turn.getWorkspaceId(), List.of(turnId))
                        .contains(turnId)) {
            return false;
        }
        turn.end(MentorTurn.Status.FAILED, error, now);
        turn.recordFailure("start_wait_expired", null);
        turns.flush();
        events.publishEvent(new MentorLedgerEvents.TurnChanged(
                turn.getWorkspaceId(),
                turn.getConversationId(),
                turn.getId(),
                turn.getStatus(),
                turn.getSurface(),
                turn.getAttempt()));
        events.publishEvent(new SlotsChanged(turn.getWorkspaceId()));
        return true;
    }

    /**
     * Publishes the turns that a worker may start again, so their clients read their reason anew. Only these turns can
     * wait for a free Heph, and the change can have taken or given back the last slot. A change in another workspace
     * counts, because all workspaces share the slots. After the commit, so that two changes that commit together each
     * read the other's. Every process publishes it, also one that does not know the capacity: a worker starts and ends
     * most turns.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishWaits(SlotsChanged changed) {
        for (MentorTurnRepository.QueuedTurn turn : turns.findClaimable(MAX_PUBLISHED)) {
            events.publishEvent(new MentorLedgerEvents.TurnChanged(
                    turn.getWorkspaceId(),
                    turn.getConversationId(),
                    turn.getId(),
                    MentorTurn.Status.QUEUED,
                    MentorTurn.Surface.valueOf(turn.getSurface()),
                    turn.getAttempt()));
        }
    }
}
