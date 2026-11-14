package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The admitted messages and destination of a Slack reply; delivery state belongs to the outbox. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ConversationSlackMessages {
    private final ConversationRepository conversations;
    private final ConversationEntryRepository entries;
    private final ConversationTurnRepository turns;
    private final ConversationTimeline timeline;

    /**
     * A turn asked in Slack, with where its reply goes.
     *
     * @param threadTs the Slack thread to reply in; {@code null} for the top level of the DM
     * @param messages Hephaestus's stored messages of the turn as the app shows them, with the feedback each showed; none
     *     while the turn runs
     * @param firstAnswer whether this is Hephaestus's first answer in Slack in this DM or thread, the one that says it
     *     answers with an AI model
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
                timeline.shown(conversation.get(), stored),
                !entries.answeredInSlackBefore(
                        workspaceId, Objects.requireNonNull(conversation.get().getId()), question.getSeq())));
    }

    /** The turn of a question, for the outbox's end callback. */
    @Transactional(readOnly = true)
    public Optional<ConversationTurn> turnForQuestion(long workspaceId, UUID questionId) {
        return entries.findByIdAndWorkspaceId(questionId, workspaceId)
                .map(ConversationEntry::getTurnId)
                .flatMap(turn -> turns.findByIdAndWorkspaceId(turn, workspaceId));
    }

    /** Binds an admitted ledger message to the Slack message that confirmed it. */
    @Transactional
    public void link(long workspaceId, UUID entryId, String ts) {
        entries.setSlackTs(workspaceId, entryId, ts);
    }
}
