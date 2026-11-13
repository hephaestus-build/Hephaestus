package de.tum.cit.aet.hephaestus.mentor;

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
public class MentorSlackMessages {
    private final MentorConversationRepository conversations;
    private final MentorEntryRepository entries;
    private final MentorTurnRepository turns;
    private final MentorTimeline timeline;

    /**
     * A turn asked in Slack, with where its reply goes.
     *
     * @param threadTs the Slack thread to reply in; {@code null} for the top level of the DM
     * @param messages Heph's stored messages of the turn as the app shows them, with the feedback each showed; none
     *     while the turn runs
     * @param firstAnswer whether this is Hephaestus's first answer in Slack in this DM or thread, the one that says it
     *     answers with an AI model
     */
    public record SlackTurn(
            MentorTurn turn,
            long accountId,
            String channelId,
            @Nullable String threadTs,
            MentorEntry question,
            List<MentorEntryDTO> messages,
            boolean firstAnswer) {

        public boolean inThread() {
            return threadTs != null;
        }

        /** Heph's answer: the last message of the turn. Its Slack post carries the reply. */
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
        Optional<MentorTurn> found = turns.findByIdAndWorkspaceId(turnId, workspaceId);
        if (found.isEmpty() || found.get().getSurface() != MentorTurn.Surface.SLACK) {
            return Optional.empty();
        }
        MentorTurn turn = found.get();
        Optional<MentorConversation> conversation =
                conversations.findByIdAndWorkspaceId(turn.getConversationId(), workspaceId);
        if (conversation.isEmpty()) {
            return Optional.empty();
        }
        Optional<MentorConversation> main = conversation.get().isThread()
                ? conversations.findByIdAndWorkspaceId(conversation.get().rootId(), workspaceId)
                : conversation;
        String channelId = main.map(MentorConversation::getSlackChannelId).orElse(null);
        if (channelId == null) {
            return Optional.empty();
        }
        String threadTs = null;
        UUID parentEntryId = conversation.get().getParentEntryId();
        if (parentEntryId != null) {
            threadTs = entries.findByIdAndWorkspaceId(parentEntryId, workspaceId)
                    .map(MentorEntry::getSlackTs)
                    .orElse(null);
            if (threadTs == null) {
                return Optional.empty();
            }
        }
        List<MentorEntry> stored =
                entries
                        .findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(workspaceId, turnId, MentorEntry.Kind.HEPH)
                        .stream()
                        .filter(entry -> !entry.isRedacted())
                        .toList();
        MentorEntry question = entries.findQuestion(turn.getWorkspaceId(), turn.getId());
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
    public Optional<MentorTurn> turnForQuestion(long workspaceId, UUID questionId) {
        return entries.findByIdAndWorkspaceId(questionId, workspaceId)
                .map(MentorEntry::getTurnId)
                .flatMap(turn -> turns.findByIdAndWorkspaceId(turn, workspaceId));
    }

    /** Binds an admitted ledger message to the Slack message that confirmed it. */
    @Transactional
    public void link(long workspaceId, UUID entryId, String ts) {
        entries.setSlackTs(workspaceId, entryId, ts);
    }
}
