package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.ObjectNode;

/**
 * Appends to and changes the Heph conversation ledger: sequence numbers, votes, Slack edits, redaction and
 * past-conversation search. Callers authorize the developer before they call.
 */
@Service
@RequiredArgsConstructor
public class MentorEntryService {

    /** The most hits {@code search_conversation} returns. */
    public static final int MAX_SEARCH_HITS = 20;

    private final MentorConversationRepository conversations;
    private final MentorEntryRepository entries;
    private final MentorTurnRepository turns;
    private final MentorRedaction redaction;
    private final MentorContextPathBuilder paths;
    private final PersonDataWriteFence writeFence;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public enum SearchScope {
        MAIN,
        /** The conversation the search runs in. */
        THIS,
        /** The main conversation and all its threads. */
        ALL,
    }

    /**
     * What {@code search_conversation} asks for.
     *
     * @param query websearch syntax; {@code null} or blank lists the newest messages
     * @param limit capped at {@link #MAX_SEARCH_HITS}
     */
    public record SearchQuery(
            @Nullable String query,
            SearchScope scope,
            @Nullable Instant before,
            @Nullable Instant after,
            int limit) {}

    public record SearchHit(
            UUID entryId,
            UUID conversationId,
            Instant createdAt,
            MentorEntry.Surface surface,
            MentorEntry.Kind kind,
            String excerpt) {}

    /**
     * Appends an entry with the conversation's next sequence number, under the conversation's lock
     * ({@link MentorConversationRepository#lockByIdAndWorkspaceId}).
     */
    @Transactional
    public MentorEntry append(long workspaceId, UUID conversationId, MentorEntryDraft draft) {
        MentorConversation conversation = lock(workspaceId, conversationId);
        MentorEntry entry = insert(conversation, draft);
        appended(conversation, List.of(entry));
        return entry;
    }

    /** @param inserted whether this call wrote the entry; {@code false} when an earlier request with its id did */
    public record Appended(MentorEntry entry, boolean inserted) {}

    /**
     * Appends a server-written entry once per {@code requestId}: a repeated request returns the entry the first one
     * wrote. The conversation lock serializes concurrent requests, so exactly one of them inserts. A request id
     * already used elsewhere is rejected.
     */
    @Transactional
    public Appended appendOnce(long workspaceId, UUID conversationId, MentorEntryDraft draft) {
        String requestId = Objects.requireNonNull(draft.requestId(), "An idempotent append needs a request id");
        MentorConversation conversation = lock(workspaceId, conversationId);
        Optional<MentorEntry> earlier = entries.findByWorkspaceIdAndRequestId(workspaceId, requestId);
        if (earlier.isPresent()) {
            MentorEntry entry = earlier.get();
            if (!entry.getConversationId().equals(conversationId) || entry.getKind() != draft.kind()) {
                throw new MentorRejectedException(MentorRejectedException.Reason.REQUEST_ID_REUSED);
            }
            return new Appended(entry, false);
        }
        MentorEntry entry = insert(conversation, draft);
        appended(conversation, List.of(entry));
        return new Appended(entry, true);
    }

    @Transactional(readOnly = true)
    public boolean exists(long workspaceId, String requestId) {
        return entries.existsByWorkspaceIdAndRequestId(workspaceId, requestId);
    }

    @Transactional(readOnly = true)
    public Optional<MentorEntry> find(long workspaceId, UUID entryId) {
        return entries.findByIdAndWorkspaceId(entryId, workspaceId);
    }

    /**
     * Sets or clears the developer's vote on one of Heph's messages. The update itself refuses an erased message, so
     * a vote cannot write over a redaction that committed first.
     */
    @Transactional
    public MentorEntry vote(long workspaceId, UUID entryId, MentorEntry.@Nullable Vote vote) {
        MentorEntry entry = require(workspaceId, entryId);
        holdWrite(workspaceId, entry.getConversationId());
        if (entries.vote(workspaceId, entryId, vote) == 0) {
            throw new MentorRejectedException(MentorRejectedException.Reason.NOT_VOTABLE);
        }
        changed(workspaceId, entry.getConversationId(), entryId);
        return require(workspaceId, entryId);
    }

    /**
     * Replaces the text of a Slack message the developer edited, and marks it edited. Heph does not answer it again.
     * Every summary and search result that copied its earlier words is erased, so Heph reads only the new text. The
     * same text again changes nothing. The update itself refuses a deleted message.
     */
    @Transactional
    public MentorEntry editSlackMessage(long workspaceId, UUID conversationId, String slackTs, String text) {
        holdWrite(workspaceId, conversationId);
        MentorEntry entry = entries.findByWorkspaceIdAndConversationIdAndSlackTs(workspaceId, conversationId, slackTs)
                .orElseThrow(() -> new EntityNotFoundException("MentorEntry", slackTs));
        if (entry.getKind() != MentorEntry.Kind.USER || entry.getSurface() != MentorEntry.Surface.SLACK) {
            throw new MentorRejectedException(MentorRejectedException.Reason.NOT_EDITABLE);
        }
        if (!entry.isRedacted() && entry.getText().equals(text)) {
            return entry;
        }
        // The tree first, as a redaction does (MentorRedaction), so a running turn's context read sees one version.
        entries.lockTreesOf(new UUID[] {entry.getId()});
        if (entries.editSlackMessage(workspaceId, entry.getId(), text) == 0) {
            throw new MentorRejectedException(MentorRejectedException.Reason.NOT_EDITABLE);
        }
        redaction.eraseCopiesOf(List.of(entry.getId()));
        changed(workspaceId, conversationId, entry.getId());
        return require(workspaceId, entry.getId());
    }

    /** Holds the write fence of the conversation's owner ({@link MentorConversationService#holdWrite}). */
    private void holdWrite(long workspaceId, UUID conversationId) {
        MentorConversationService.holdWrite(
                writeFence,
                conversations
                        .findAccountIdByIdAndWorkspaceId(conversationId, workspaceId)
                        .orElseThrow(
                                () -> new EntityNotFoundException("MentorConversation", conversationId.toString())));
    }

    /** The Slack message's entry in a conversation, if Hephaestus stored it. */
    @Transactional(readOnly = true)
    public Optional<MentorEntry> findBySlackTs(long workspaceId, UUID conversationId, String slackTs) {
        return entries.findByWorkspaceIdAndConversationIdAndSlackTs(workspaceId, conversationId, slackTs);
    }

    /** The Slack message's entry in a main conversation or one of its threads, if Hephaestus stored it. */
    @Transactional(readOnly = true)
    public Optional<MentorEntry> findSlackMessage(long workspaceId, UUID mainConversationId, String slackTs) {
        return entries.findInTreeBySlackTs(workspaceId, mainConversationId, slackTs).stream()
                .findFirst();
    }

    /**
     * Deletes a message the developer wrote: its content and every copy of it ({@link MentorRedaction}). Its turn
     * stops if it has not ended: Heph must not answer words the developer took back. Deleting it again changes nothing.
     */
    @Transactional
    public void deleteMessage(long workspaceId, UUID entryId) {
        MentorEntry entry = require(workspaceId, entryId);
        if (entry.getKind() != MentorEntry.Kind.USER) {
            throw new MentorRejectedException(MentorRejectedException.Reason.NOT_DELETABLE);
        }
        if (entry.isRedacted()) {
            return;
        }
        UUID turnId = entry.getTurnId();
        if (turnId != null) {
            turns.lockById(turnId).ifPresent(this::stop);
        }
        redaction.redact(List.of(entryId));
    }

    /**
     * Erases every entry written on the Slack surface in a workspace when its Slack content is erased: the developers'
     * Slack messages and everything Heph wrote in Slack turns. Slack turns that have not ended stop.
     */
    @Transactional
    public void redactSlackEntries(long workspaceId) {
        for (MentorTurn turn : turns.findActiveByWorkspaceIdAndSurface(workspaceId, MentorTurn.Surface.SLACK)) {
            turns.lockById(turn.getId()).ifPresent(this::stop);
        }
        redaction.redact(entries.findUnredactedIdsByWorkspaceIdAndSurface(workspaceId, MentorEntry.Surface.SLACK));
    }

    /** Full-text search over a conversation tree ({@link MentorEntryRepository#searchByWorkspaceId}). */
    @Transactional(readOnly = true)
    public List<SearchHit> search(long workspaceId, UUID conversationId, SearchQuery search) {
        MentorConversation conversation = conversations
                .findByIdAndWorkspaceId(conversationId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("MentorConversation", conversationId.toString()));
        List<UUID> scoped = new ArrayList<>();
        switch (search.scope()) {
            case MAIN -> scoped.add(conversation.rootId());
            case THIS -> scoped.add(conversation.getId());
            case ALL -> {
                scoped.add(conversation.rootId());
                conversations.findByWorkspaceIdAndParentConversationId(workspaceId, conversation.rootId()).stream()
                        .map(MentorConversation::getId)
                        .forEach(scoped::add);
            }
        }
        String asked = search.query();
        String query = asked == null || asked.isBlank() ? null : asked;
        return entries
                .searchByWorkspaceId(
                        workspaceId,
                        scoped,
                        query,
                        search.before(),
                        search.after(),
                        Math.clamp(search.limit(), 1, MAX_SEARCH_HITS))
                .stream()
                .map(hit -> new SearchHit(
                        hit.getId(),
                        hit.getConversationId(),
                        hit.getCreatedAt(),
                        MentorEntry.Surface.valueOf(hit.getSurface()),
                        MentorEntry.Kind.valueOf(hit.getKind()),
                        hit.getExcerpt()))
                .toList();
    }

    MentorEntry require(long workspaceId, UUID entryId) {
        return entries.findByIdAndWorkspaceId(entryId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("MentorEntry", entryId.toString()));
    }

    /**
     * Appends to a conversation whose row the caller's transaction already locked. The caller tells listeners with
     * {@link #appended} once it appended all it appends.
     */
    MentorEntry insert(MentorConversation conversation, MentorEntryDraft draft) {
        Instant now = clock.instant();
        long seq = entries.findMaxSeqByWorkspaceIdAndConversationId(conversation.getWorkspaceId(), conversation.getId())
                + 1;
        MentorEntryDraft stored = draft.kind() == MentorEntry.Kind.SUMMARY ? withCoverage(conversation, draft) : draft;
        MentorEntry entry = entries.saveAndFlush(new MentorEntry(conversation, seq, stored, now));
        conversation.touch(now);
        return entry;
    }

    /** Tells listeners about the messages among these entries; the app shows no other kind. */
    void appended(MentorConversation conversation, List<MentorEntry> appended) {
        List<UUID> messages = appended.stream()
                .filter(entry -> entry.getKind().isMessage())
                .map(MentorEntry::getId)
                .toList();
        MentorLedgerEvents.EntryChanged.of(conversation.getWorkspaceId(), conversation.getId(), messages)
                .forEach(events::publishEvent);
    }

    MentorConversation lock(long workspaceId, UUID conversationId) {
        return conversations
                .lockByIdAndWorkspaceId(conversationId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("MentorConversation", conversationId.toString()));
    }

    /**
     * Locks a conversation for an append that may copy its tree's history: its main conversation's row first, as a
     * redaction does ({@link MentorRedaction}), then a thread's own row.
     */
    MentorConversation lockWithTree(long workspaceId, UUID conversationId) {
        MentorConversation conversation = conversations
                .findByIdAndWorkspaceId(conversationId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("MentorConversation", conversationId.toString()));
        if (conversation.isThread()) {
            lock(workspaceId, conversation.rootId());
        }
        return lock(workspaceId, conversationId);
    }

    /** Records the summary's {@code fromSeq..toSeq} coverage for {@link MentorRedaction}. */
    private MentorEntryDraft withCoverage(MentorConversation conversation, MentorEntryDraft draft) {
        if (!(draft.payload() instanceof ObjectNode summary)) {
            throw new IllegalArgumentException("A summary stores Pi's compaction entry");
        }
        MentorContextPathBuilder.Coverage coverage =
                paths.coverage(conversation, summary.path("firstKeptEntryId").asString(null));
        ObjectNode covered = summary.deepCopy();
        covered.put("fromSeq", coverage.fromSeq());
        covered.put("toSeq", coverage.toSeq());
        return new MentorEntryDraft(
                draft.kind(),
                draft.surface(),
                draft.text(),
                covered,
                draft.turnId(),
                draft.requestId(),
                draft.slackTs());
    }

    /**
     * Publishes a change of a turn that already waited. A start, an end or a requeue takes or gives back a Heph slot,
     * so the reason every queued turn waits for can change ({@link MentorTurnWaits}): the slots change once per
     * transaction, however many turns it moves. A stop gives back no slot: a queued turn held none, and a running one
     * keeps its host until the host ends it. It can only let the next question of its own conversation start.
     *
     * @param slotsChanged whether the turn took or gave back a Heph slot
     */
    void turnChanged(MentorTurn turn, boolean slotsChanged) {
        events.publishEvent(MentorLedgerEvents.TurnChanged.of(turn));
        if (slotsChanged) {
            slotsChanged(turn.getWorkspaceId());
        } else if (turn.getStatus().isTerminal()) {
            turns.findByWorkspaceIdAndConversationIdAndStatus(
                            turn.getWorkspaceId(), turn.getConversationId(), MentorTurn.Status.QUEUED)
                    .forEach(next -> events.publishEvent(MentorLedgerEvents.TurnChanged.of(next)));
        }
    }

    private void slotsChanged(long workspaceId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            if (TransactionSynchronizationManager.hasResource(MentorTurnWaits.SlotsChanged.class)) {
                return;
            }
            TransactionSynchronizationManager.bindResource(MentorTurnWaits.SlotsChanged.class, true);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(MentorTurnWaits.SlotsChanged.class);
                }
            });
        }
        events.publishEvent(new MentorTurnWaits.SlotsChanged(workspaceId));
    }

    /** Stops a turn the caller locked, unless it ended. */
    private void stop(MentorTurn turn) {
        if (turn.stopIfActive(clock.instant())) {
            turnChanged(turn, false);
        }
    }

    private void changed(long workspaceId, UUID conversationId, UUID entryId) {
        events.publishEvent(new MentorLedgerEvents.EntryChanged(workspaceId, conversationId, List.of(entryId)));
    }
}
