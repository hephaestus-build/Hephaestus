package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The admitted messages and destination of a Slack reply; delivery state belongs to the outbox. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ConversationSlackMessages {
    private static final List<ConversationTurn.Status> ENDED = Arrays.stream(ConversationTurn.Status.values())
            .filter(ConversationTurn.Status::isTerminal)
            .toList();

    private final ConversationRepository conversations;
    private final ConversationEntryRepository entries;
    private final ConversationTurnRepository turns;
    private final ConversationEntryViews views;
    private final ConversationContextChanges contextChanges;

    /** What {@link #admitReply} decided for a reply that was read. */
    public enum ReplyAdmission {
        /** The write began: the provider call may go. */
        STARTED,
        /** The history the reply was read from changed: nothing is posted. */
        WITHDRAWN,
        /** The action's lease is gone: another attempt owns it. */
        ABANDONED
    }

    /**
     * A turn asked in Slack, with where its reply goes.
     *
     * @param threadTs the Slack thread to reply in; {@code null} for the top level of the DM
     * @param messages Hephaestus's stored messages of the turn as the app shows them, with the feedback each showed; none
     *     while the turn runs
     * @param firstAnswer whether this is Hephaestus's first answer in Slack in this DM or thread, the one that says it
     *     is an AI system
     */
    public record SlackTurn(
            ConversationTurn turn,
            long accountId,
            String channelId,
            @Nullable String threadTs,
            ConversationEntry question,
            List<ConversationEntryDTO> messages,
            boolean firstAnswer) {

        public boolean inThread() {
            return threadTs != null;
        }

        /** Hephaestus's answer: the last message of the turn. Its Slack post carries the reply. */
        public @Nullable UUID answerId() {
            return messages.isEmpty() ? null : messages.getLast().id();
        }
    }

    /**
     * The Slack turn, if it is one whose reply can go to Slack: asked in Slack, in a conversation that still knows its
     * Slack DM, and for a thread, on a parent message that Slack knows.
     */
    @Transactional(readOnly = true)
    public Optional<SlackTurn> slackTurn(long workspaceId, UUID turnId) {
        Optional<ConversationTurn> found = turns.findByIdAndWorkspaceId(turnId, workspaceId);
        if (found.isEmpty() || found.get().getSurface() != ConversationTurn.Surface.SLACK) {
            return Optional.empty();
        }
        ConversationTurn turn = found.get();
        Optional<Conversation> conversation =
                conversations.findByIdAndWorkspaceId(turn.getConversationId(), workspaceId);
        if (conversation.isEmpty()) {
            return Optional.empty();
        }
        Optional<Conversation> main = conversation.get().isThread()
                ? conversations.findByIdAndWorkspaceId(conversation.get().rootId(), workspaceId)
                : conversation;
        String channelId = main.map(Conversation::getSlackChannelId).orElse(null);
        if (channelId == null) {
            return Optional.empty();
        }
        String threadTs = null;
        UUID parentEntryId = conversation.get().getParentEntryId();
        if (parentEntryId != null) {
            threadTs = entries.findByIdAndWorkspaceId(parentEntryId, workspaceId)
                    .map(ConversationEntry::getSlackTs)
                    .orElse(null);
            if (threadTs == null) {
                return Optional.empty();
            }
        }
        List<ConversationEntry> stored = entries
                .findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(workspaceId, turnId, ConversationEntry.Kind.ANSWER)
                .stream()
                .filter(entry -> !entry.isRedacted())
                .toList();
        ConversationEntry question = entries.findStimulus(turn.getWorkspaceId(), turn.getId());
        return Optional.of(new SlackTurn(
                turn,
                conversation.get().getAccountId(),
                channelId,
                threadTs,
                question,
                views.shown(conversation.get(), stored),
                !entries.answeredInSlackBefore(
                        workspaceId, Objects.requireNonNull(conversation.get().getId()), question.getSeq())));
    }

    /**
     * The question of the account's ended Slack turn, whose reply the outbox posts. One statement checks the owner and
     * finds the question, so a change of owner cannot fall between two reads, and it reads no content.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> endedSlackQuestion(long workspaceId, long accountId, UUID turnId) {
        return entries.findQuestionOfEndedTurn(
                workspaceId, accountId, turnId, ConversationTurn.Surface.SLACK, ENDED, ConversationEntry.Kind.USER);
    }

    /** The turn of a question, for the outbox's end callback. */
    @Transactional(readOnly = true)
    public Optional<ConversationTurn> turnForQuestion(long workspaceId, UUID questionId) {
        return entries.findByIdAndWorkspaceId(questionId, workspaceId)
                .map(ConversationEntry::getTurnId)
                .flatMap(turn -> turns.findByIdAndWorkspaceId(turn, workspaceId));
    }

    /**
     * Admits the write of a Slack reply that was {@code read} before. It holds the account's history lock, shared, which
     * a removal holds exclusively ({@link ConversationContextChanges#lockHistoryForRemoval}). Under the lock it checks
     * three things: the turn still ends as it was read, its question stands, and no entry of the account's conversations
     * lost its words since the turn started. Only then does {@code beginWrite} commit. So a removal commits either before
     * this check, and the reply is withdrawn, or after the write began.
     */
    @Transactional
    public ReplyAdmission admitReply(SlackTurn read, BooleanSupplier beginWrite) {
        ConversationTurn turn = read.turn();
        long workspaceId = turn.getWorkspaceId();
        contextChanges.shareHistory(workspaceId, read.accountId());
        Instant since = turn.getStartedAt() == null ? turn.getCreatedAt() : turn.getStartedAt();
        boolean unchanged = turns.findByIdAndWorkspaceId(turn.getId(), workspaceId)
                        .filter(now -> now.getStatus() == turn.getStatus())
                        .isPresent()
                && !entries.findStimulus(workspaceId, turn.getId()).isRedacted()
                && !readableMessageRemovedSince(workspaceId, turn, read.question(), since);
        if (!unchanged) return ReplyAdmission.WITHDRAWN;
        return beginWrite.getAsBoolean() ? ReplyAdmission.STARTED : ReplyAdmission.ABANDONED;
    }

    /** Whether a message that the turn could read was removed since {@code since} (its question has its own check). */
    private boolean readableMessageRemovedSince(
            long workspaceId, ConversationTurn turn, ConversationEntry question, Instant since) {
        Conversation conversation = conversations
                .findByIdAndWorkspaceId(turn.getConversationId(), workspaceId)
                .orElseThrow();
        // A main conversation has no parent: its own condition covers it, and the thread condition matches nothing.
        UUID mainId = conversation.isThread() ? conversation.rootId() : conversation.getId();
        long parentSeq = -1;
        UUID parentEntryId = conversation.getParentEntryId();
        if (conversation.isThread() && parentEntryId != null) {
            parentSeq = entries.findByIdAndWorkspaceId(parentEntryId, workspaceId)
                    .map(ConversationEntry::getSeq)
                    .orElse(-1L);
        }
        return entries.existsReadableMessageRedactedSince(
                workspaceId,
                Objects.requireNonNull(conversation.getId()),
                question.getSeq(),
                Objects.requireNonNull(mainId),
                parentSeq,
                since);
    }

    /** Binds an admitted ledger message to the Slack message that confirmed it. */
    @Transactional
    public void link(long workspaceId, UUID entryId, String ts) {
        entries.setSlackTs(workspaceId, entryId, ts);
    }
}
