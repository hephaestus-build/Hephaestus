package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds and creates Heph conversations.
 *
 * <p>Actor rule: an account can act through several SCM actors in one workspace. Its main conversation is found by
 * any of them. A write creates the conversation under the actor the request selected, and a write through a different
 * selected actor moves the conversation (and its threads) to that actor, so the developer the next turn reads about is
 * the one the conversation records. Reads never move it.
 *
 * <p>Erasure. A write that creates a conversation or stores a question holds the person-data write fence of the
 * developer first ({@link #holdWrite}), before any ledger lock. An erasure of the developer waits for the write, and
 * a write after the erasure was requested is refused, so no conversation or question outlives the erasure.
 */
@Service
@RequiredArgsConstructor
public class MentorConversationService {

    private final MentorConversationRepository conversations;
    private final MentorEntryRepository entries;
    private final PersonDataWriteFence writeFence;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public MentorConversation require(long workspaceId, UUID conversationId) {
        return conversations
                .findByIdAndWorkspaceId(conversationId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("MentorConversation", conversationId.toString()));
    }

    /** The account's main conversation for a write by {@code selectedActorId}; see the class comment. */
    @Transactional
    public MentorConversation findOrCreateMain(
            long workspaceId, long selectedActorId, Collection<Long> accountActorIds) {
        holdWrite(writeFence, selectedActorId);
        Set<Long> actors = new TreeSet<>(accountActorIds);
        actors.add(selectedActorId);
        // Serializes the first write of an account across its actors: the unique index on mains is per actor.
        conversations.lockMainCreation(
                "mentor-main:" + workspaceId + ":" + actors.iterator().next());
        Optional<MentorConversation> existing =
                conversations.findMainsByWorkspaceIdAndUserIdIn(workspaceId, actors).stream()
                        .findFirst();
        if (existing.isEmpty()) {
            return conversations.save(MentorConversation.main(workspaceId, selectedActorId, clock.instant()));
        }
        MentorConversation main = existing.get();
        if (main.getUserId() != selectedActorId) {
            main.moveTo(selectedActorId);
            conversations.moveThreadsByWorkspaceId(workspaceId, main.getId(), selectedActorId);
        }
        return main;
    }

    /**
     * The thread on {@code parentEntryId}, created on first use. A thread forks a main conversation at one of its
     * messages: a question, an answer or a weekly digest. Threads do not nest.
     */
    @Transactional
    public MentorConversation openThread(long workspaceId, UUID parentEntryId) {
        UUID mainId = entries.findConversationIdByIdAndWorkspaceId(parentEntryId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("MentorEntry", parentEntryId.toString()));
        holdWrite(
                writeFence,
                conversations
                        .findUserIdByIdAndWorkspaceId(mainId, workspaceId)
                        .orElseThrow(() -> new EntityNotFoundException("MentorConversation", mainId.toString())));
        // The main first, before anything is read: one thread per parent, concurrent openers wait here and the second
        // finds the first's thread; a redaction or a move to another actor commits before the reads below.
        MentorConversation main = conversations
                .lockByIdAndWorkspaceId(mainId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("MentorConversation", mainId.toString()));
        MentorEntry parent = entries.findByIdAndWorkspaceId(parentEntryId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("MentorEntry", parentEntryId.toString()));
        if (main.isThread() || !parent.getKind().isMessage() || parent.isRedacted()) {
            throw new MentorRejectedException(MentorRejectedException.Reason.NOT_A_THREAD_PARENT);
        }
        return conversations
                .findByWorkspaceIdAndParentEntryId(workspaceId, parentEntryId)
                .orElseGet(() -> conversations.save(MentorConversation.thread(main, parentEntryId, clock.instant())));
    }

    /**
     * Holds the person-data write fence of the developer a write stores content about, until the transaction commits.
     * Call it before any ledger lock.
     *
     * @throws MentorRejectedException when the developer's data was erased or its erasure was requested
     */
    static void holdWrite(PersonDataWriteFence writeFence, long actorId) {
        if (!writeFence.holdForUserWrite(actorId)) {
            throw new MentorRejectedException(MentorRejectedException.Reason.PERSON_ERASED);
        }
    }

    /**
     * Records that the developer read their conversation up to {@code at}. The marker only moves forward, and never
     * past now: an earlier time, as a second device sends it, changes nothing. A move tells the developer's other open
     * streams after commit ({@link MentorLedgerEvents.ReadChanged}). A developer without a conversation has nothing to
     * read.
     *
     * @return the marker after the call, or {@code null} without a conversation
     */
    @Transactional
    public @Nullable Instant markRead(long workspaceId, Collection<Long> accountActorIds, Instant at) {
        Instant read = at.isAfter(clock.instant()) ? clock.instant() : at;
        Optional<MentorConversation> found =
                conversations.findMainsByWorkspaceIdAndUserIdIn(workspaceId, accountActorIds).stream()
                        .findFirst();
        if (found.isEmpty()) {
            return null;
        }
        MentorConversation main = found.get();
        if (conversations.markRead(main.getId(), workspaceId, read) == 0) {
            // The marker is already here or later, possibly written by a request that committed after the row above
            // was read: the database has the current one.
            return conversations.findLastReadAt(main.getId(), workspaceId);
        }
        events.publishEvent(new MentorLedgerEvents.ReadChanged(workspaceId, main.getId(), main.getUserId(), read));
        return read;
    }

    /** The main conversation projected into this Slack DM, without creating or moving it. */
    @Transactional(readOnly = true)
    public Optional<MentorConversation> findMainBySlack(
            long workspaceId, String teamId, String slackUserId, String channelId) {
        return conversations.findMainsBySlackIdentity(workspaceId, teamId, slackUserId, channelId).stream()
                .findFirst();
    }

    /** The thread on {@code parentEntryId}, if one was opened. */
    @Transactional(readOnly = true)
    public Optional<MentorConversation> findThread(long workspaceId, UUID parentEntryId) {
        return conversations.findByWorkspaceIdAndParentEntryId(workspaceId, parentEntryId);
    }

    /** Records the Slack DM that this main conversation's top level is projected into. */
    @Transactional
    public MentorConversation bindSlackIdentity(
            long workspaceId, UUID conversationId, String teamId, String userId, String channelId) {
        MentorConversation conversation = require(workspaceId, conversationId);
        conversation.bindSlack(teamId, userId, channelId);
        return conversation;
    }

    /** Forgets every Slack identity in the workspace when its Slack content is erased. */
    @Transactional
    public int clearSlackIdentities(long workspaceId) {
        return conversations.clearSlackIdentitiesByWorkspaceId(workspaceId);
    }
}
