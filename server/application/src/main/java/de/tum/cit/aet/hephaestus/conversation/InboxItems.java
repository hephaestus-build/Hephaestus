package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.conversation.spi.TurnRuns;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The inbox of each thread (ADR 0120): the items that no person typed, and the start policy in code that decides each
 * one before any model runs. A background turn for an item starts only while the thread is idle. A person's question
 * never waits behind one: once the question commits, the thread's background turns stop, as the person's own Stop stops
 * a turn, and the item waits for the next idle moment. Every decision is recorded on the item as its reason.
 *
 * <p>Lock order. A start takes the person's write fence, then the thread's lock, then the item; it reads turns without
 * locking them, and only a submission, which takes the same thread lock, adds a person's turn. A yield locks the turns
 * of the thread first, as Stop does, and never the thread: a turn's holder locks its turn before the thread when it
 * ends, so no path here waits for the thread while it holds a turn.
 */
@Service
@RequiredArgsConstructor
public class InboxItems {
    /** How long after the person's last message an item waits: they may still be reading or typing (ADR 0139). */
    static final Duration IDLE_AFTER = Duration.ofMinutes(2);

    /** The turns of an item that may run and fail; a turn that a question stopped before it ran is not charged. */
    static final int MAX_ATTEMPTS = 5;

    private final InboxItemRepository items;
    private final ConversationRepository conversations;
    private final ConversationTurnRepository turns;
    private final ConversationEntryRepository entries;
    private final ConversationEntryService entryService;
    private final PersonDataWriteFence writeFence;
    private final TurnRuns runs;
    private final TransactionTemplate transactions;

    /** What the item's producer did at its start. */
    public sealed interface Start {}

    /** A turn started for the item. */
    public record Started(UUID turnId) implements Start {}

    /** The producer had nothing to say, or the item is no longer eligible. */
    public record Silent(InboxItem.Reason reason) implements Start {}

    /** How an item ends after its turn ended. */
    public record Ended(InboxItem.State state, InboxItem.Reason reason) {}

    /** What a kind of item does at its start and when its turn ends. */
    public interface Producer {
        /**
         * Starts the item's turn under the thread's lock, or stays silent.
         *
         * @param start how many turns started for the item before: 0 for its first
         */
        Start start(long workspaceId, UUID conversationId, long accountId, int start, JsonNode payload);

        /** The item's end after its turn ended with {@code status}; empty to start it again. */
        Optional<Ended> ended(long workspaceId, UUID turnId, ConversationTurn.Status status);
    }

    /** An open, due item, as the start pass reads it. */
    public record Due(UUID id, long workspaceId, UUID conversationId) {}

    /**
     * A new item of a thread.
     *
     * @param payload the IDs and values its producer needs at its start, never text
     */
    public record NewItem(
            long workspaceId,
            UUID conversationId,
            InboxItem.Kind kind,
            String sourceKey,
            InboxItem.Custody custody,
            Instant dueAt,
            Instant deadlineAt,
            JsonNode payload) {}

    /**
     * Writes the item on the thread once: the same source on the same thread writes nothing again. Its producer holds
     * the thread's single schedule lock, and the unique key refuses a second writer that slips past it.
     *
     * @return the item, when this call wrote it
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Due> enqueue(NewItem item, Instant now) {
        if (items.existsByWorkspaceIdAndConversationIdAndSourceKey(
                item.workspaceId(), item.conversationId(), item.sourceKey())) return Optional.empty();
        var saved = items.save(new InboxItem(UUID.randomUUID(), item, now));
        return Optional.of(new Due(saved.getId(), saved.getWorkspaceId(), saved.getConversationId()));
    }

    /** The open items of a kind that are due at {@code now}, oldest first. */
    @Transactional(readOnly = true)
    public List<Due> due(InboxItem.Kind kind, Instant now, int limit) {
        return items.findDue(kind.name(), now, limit).stream()
                .map(row -> new Due(row.id(), row.workspaceId(), row.conversationId()))
                .toList();
    }

    /**
     * Decides one item now, in its own transaction: settles it when its turn ended, its deadline passed or its attempts
     * are spent; defers it while the person is active; otherwise lets its producer start its turn, or stay silent.
     *
     * @return the item's state after the decision
     */
    public InboxItem.State decide(Due due, Producer producer, Instant now) {
        return Objects.requireNonNull(transactions.execute(status -> {
            long accountId = conversations
                    .findAccountIdByIdAndWorkspaceId(due.conversationId(), due.workspaceId())
                    .orElse(-1L);
            boolean writable = accountId >= 0 && writeFence.holdForAccountWrite(accountId);
            entryService.lock(due.workspaceId(), due.conversationId());
            var item = items.lockByIdAndWorkspaceId(due.id(), due.workspaceId()).orElse(null);
            if (item == null) return InboxItem.State.DROPPED;
            if (!item.getState().open()) return item.getState();
            if (item.getState() == InboxItem.State.CLAIMED) settleClaim(item, producer, now);
            if (item.getState() != InboxItem.State.PENDING) return item.getState();
            if (!writable) {
                item.settle(InboxItem.State.DROPPED, InboxItem.Reason.NO_LONGER_ELIGIBLE, now);
            } else if (!now.isBefore(item.getDeadlineAt())) {
                item.settle(InboxItem.State.EXPIRED, InboxItem.Reason.DEADLINE_PASSED, now);
            } else if (item.getAttempts() >= MAX_ATTEMPTS) {
                item.settle(InboxItem.State.EXPIRED, InboxItem.Reason.ATTEMPTS_SPENT, now);
            } else if (!idle(item, now)) {
                item.defer(InboxItem.Reason.DEFERRED_ACTIVE);
            } else {
                switch (producer.start(
                        item.getWorkspaceId(),
                        item.getConversationId(),
                        accountId,
                        item.getStarts(),
                        item.getPayload())) {
                    case Started started -> item.claim(started.turnId());
                    case Silent silent ->
                        item.settle(
                                silent.reason() == InboxItem.Reason.NOTHING_TO_SAY
                                        ? InboxItem.State.CONSUMED
                                        : InboxItem.State.DROPPED,
                                silent.reason(),
                                now);
                }
            }
            return item.getState();
        }));
    }

    /**
     * After a person's question commits, the thread's background turns yield to it: a queued one ends, and a running one
     * stops as the person's Stop stops a turn, so its holder settles within the stop grace and stores nothing. The item
     * it held waits for the next idle moment ({@link #decide} sees the stopped turn). If this misses, the start pass
     * repairs it through {@link #yieldWhereQuestionsWait}.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void yieldToQuestion(ConversationLedgerEvents.TurnChanged changed) {
        if (changed.surface() == ConversationTurn.Surface.SYSTEM || changed.status() != ConversationTurn.Status.QUEUED)
            return;
        yieldThread(changed.workspaceId(), changed.conversationId());
    }

    /**
     * The repair of a missed yield: stops the background turns of every thread in which a person's question waits. The
     * start pass runs it first.
     *
     * @return how many threads yielded
     */
    public int yieldWhereQuestionsWait(int limit) {
        int yielded = 0;
        for (var thread : turns.findThreadsWithBackgroundBeforeQuestion(limit)) {
            Integer stopped =
                    transactions.execute(status -> yieldThread(thread.getWorkspaceId(), thread.getConversationId()));
            if (stopped != null && stopped > 0) yielded++;
        }
        return yielded;
    }

    /**
     * Stops the thread's background turns one at a time, each locked anew: ending a queued run clears the persistence
     * context, so a turn read before it would be detached.
     */
    private int yieldThread(long workspaceId, UUID conversationId) {
        int stopped = 0;
        var background = turns.lockActiveByWorkspaceIdAndConversationId(workspaceId, conversationId).stream()
                .filter(turn -> turn.getSurface() == ConversationTurn.Surface.SYSTEM)
                .map(ConversationTurn::getId)
                .toList();
        for (UUID id : background) {
            var turn = turns.lockByIdAndWorkspaceId(id, workspaceId).orElse(null);
            if (turn == null) continue;
            boolean running = turn.getStatus() == ConversationTurn.Status.RUNNING;
            if (!turn.stopIfActive(turns.databaseClock())) continue;
            runs.stopped(workspaceId, turn.getRunId(), running);
            entryService.turnChanged(turn, true);
            stopped++;
        }
        return stopped;
    }

    /** Settles a claimed item by its ended turn, as its producer judges it, or lets it start again. */
    private void settleClaim(InboxItem item, Producer producer, Instant now) {
        UUID turnId = Objects.requireNonNull(item.getTurnId());
        var turn = turns.findByIdAndWorkspaceId(turnId, item.getWorkspaceId()).orElse(null);
        if (turn == null) {
            item.release(InboxItem.Reason.NOT_STARTED, false);
            return;
        }
        if (!turn.getStatus().isTerminal()) return;
        var ended = producer.ended(item.getWorkspaceId(), turnId, turn.getStatus());
        if (ended.isPresent()) {
            item.settle(ended.get().state(), ended.get().reason(), now);
            return;
        }
        boolean ran = turn.getStartedAt() != null;
        item.release(
                turn.getStatus() == ConversationTurn.Status.STOPPED
                        ? InboxItem.Reason.CANCELLED_BY_MESSAGE
                        : InboxItem.Reason.NOT_STARTED,
                ran);
    }

    /**
     * Idle: no person's turn waits or runs in the thread, and the person wrote nothing in the last {@link #IDLE_AFTER}.
     * Read under the thread's lock and without locking turns: only a submission, which takes the same lock, adds a
     * person's turn.
     */
    private boolean idle(InboxItem item, Instant now) {
        return !turns.existsActiveQuestion(item.getWorkspaceId(), item.getConversationId())
                && !entries.existsByWorkspaceIdAndConversationIdAndKindAndCreatedAtAfter(
                        item.getWorkspaceId(),
                        item.getConversationId(),
                        ConversationEntry.Kind.USER,
                        now.minus(IDLE_AFTER));
    }
}
