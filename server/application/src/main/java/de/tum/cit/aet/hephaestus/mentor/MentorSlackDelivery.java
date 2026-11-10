package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The ledger's part of projecting Slack turns into Slack: where a turn's reply goes, and what a post's outcome records
 * in the ledger. The post itself is an outbox action ({@link MentorSlackReplies}), which owns its intent, attempts and
 * outcome.
 *
 * <p>The question of a Slack turn carries the state of the whole reply to it, and the server that claims it projects
 * the turn. Heph's answer carries only the {@code ts} of the Slack message that shows it, so a Slack thread on that
 * message finds it. A weekly digest carries the state and {@code ts} of its own note.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class MentorSlackDelivery {

    private final MentorConversationRepository conversations;
    private final MentorEntryRepository entries;
    private final MentorSlackDeliveryRepository deliveries;
    private final MentorTurnRepository turns;
    private final MentorTimeline timeline;
    private final MentorTurnProgress progress;
    private final PersonDataWriteFence writeFence;

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
                !deliveries.answeredInSlackBefore(
                        workspaceId, Objects.requireNonNull(conversation.get().getId()), question.getSeq())));
    }

    /**
     * The Slack work of a Heph thread when a session call reads it ({@link #callInThreadSession}).
     *
     * @param runningTurnId the thread's Slack turn that runs now; {@code null} when none runs, also when one waits
     * @param runningAttempt the attempt of that turn that runs
     */
    public record ThreadSession(@Nullable UUID runningTurnId, int runningAttempt) {

        /** Whether Heph works in the thread now: Slack shows its agent session as processing, with Stop. */
        public boolean working() {
            return runningTurnId != null;
        }

        /** Whether this attempt of this turn is the thread's running Slack work. */
        public boolean runs(UUID turnId, int attempt) {
            return turnId.equals(runningTurnId) && attempt == runningAttempt;
        }
    }

    /** One Slack call that changes the agent session of a Heph thread's Slack thread. */
    @FunctionalInterface
    public interface ThreadSessionCall<T> {

        /** @return what the call returned; {@code null} when it made no call */
        @Nullable
        T call(ThreadSession session);
    }

    /**
     * Makes one Slack call that changes the agent session of the Slack thread of a Heph thread: its status, or the
     * start or the end of a stream in it. The call reads the thread's Slack work and acts on it. Slack keeps the
     * session status that the last such call set, so these calls must reach Slack in the order of the work they read.
     *
     * <p>First the developer's person-data write fence is held ({@link PersonDataWriteFence#holdForAccountWrite}), as
     * for every write about the developer and before any ledger lock: a developer whose data is erased, or whose
     * erasure was requested, gets no call, and an erasure waits for a call that it admitted. Then the main
     * conversation's row is shared and the thread's row is locked, in the order of the conversation tree
     * ({@link MentorConversationService#openThread}), until the call returns. Thus on every server the session calls
     * of one thread run one at a time, and each reads the thread's Slack work after the call before it ended. A claim
     * of a turn takes neither row, so a turn can start meanwhile; the next session call then sees it. Other threads of
     * the main conversation share its row and do not wait. Inside, the turns and entries are only read: the caller
     * records what Slack answered after this transaction ends.
     *
     * @param conversationId the Heph thread
     * @param channelId the developer's DM that the call names
     * @param threadTs the Slack thread that the call names
     * @return what the call returned; empty, without a call, when the fence refuses the developer, or the conversation
     *     is no longer a thread of that Slack thread in that DM
     */
    @Transactional
    public <T> Optional<T> callInThreadSession(
            long workspaceId, UUID conversationId, String channelId, String threadTs, ThreadSessionCall<T> call) {
        Optional<MentorConversation> thread = conversations.findByIdAndWorkspaceId(conversationId, workspaceId);
        if (thread.isEmpty()) {
            return Optional.empty();
        }
        UUID parentEntryId = thread.get().getParentEntryId();
        if (parentEntryId == null
                || !writeFence.holdForAccountWrite(thread.get().getAccountId())) {
            return Optional.empty();
        }
        UUID mainId = thread.get().rootId();
        if (conversations.shareByIdAndWorkspaceId(mainId, workspaceId).isEmpty()
                || conversations
                        .lockByIdAndWorkspaceId(conversationId, workspaceId)
                        .isEmpty()) {
            return Optional.empty();
        }
        boolean bound = conversations
                        .findByIdAndWorkspaceId(mainId, workspaceId)
                        .map(main -> channelId.equals(main.getSlackChannelId()))
                        .orElse(false)
                && entries.findByIdAndWorkspaceId(parentEntryId, workspaceId)
                        .map(parent -> threadTs.equals(parent.getSlackTs()))
                        .orElse(false);
        if (!bound) {
            return Optional.empty();
        }
        ThreadSession session = turns.findRunningSlackTurn(workspaceId, conversationId)
                .map(running -> new ThreadSession(running.getId(), running.getAttempt()))
                .orElseGet(() -> new ThreadSession(null, 0));
        return Optional.ofNullable(call.call(session));
    }

    /** The turn as it is now: one read for each streamed piece. Its partial answer is in {@link #writing}. */
    @Transactional(readOnly = true)
    public Optional<MentorTurn> turn(long workspaceId, UUID turnId) {
        return turns.findByIdAndWorkspaceId(turnId, workspaceId);
    }

    /**
     * What a Slack stream may show of a running turn's partial answer: a stream only grows, and the feedback after
     * Heph's first link tool call reaches it only with the stored answer. {@code null} when this process has no
     * partial answer yet, which says nothing about what the answer holds.
     */
    public @Nullable String writing(MentorTurn turn) {
        JsonNode partial = progress.snapshot(turn);
        return partial == null ? null : MentorPiEntries.partialText(partial, true);
    }

    /** What Hephaestus does in a running turn, in the developer's words, or {@code null} before its first snapshot. */
    public @Nullable String stage(MentorTurn turn) {
        JsonNode partial = progress.snapshot(turn);
        return partial == null ? null : MentorTurnPartialDTO.from(partial).stage();
    }

    /** Records how an entry's post ended, in the transaction that ended its action. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ended(long workspaceId, UUID entryId, MentorEntry.SlackDelivery outcome) {
        deliveries.setSlackDelivery(workspaceId, entryId, outcome);
    }

    /** Records the receipts that a post's outcome decides, in one transaction. */
    @Transactional
    public void settle(Runnable receipts) {
        receipts.run();
    }

    /**
     * Settles a post that reached Slack.
     *
     * @param shows the entry that the Slack message shows and that takes its {@code ts}: a weekly digest, or Heph's
     *     answer; {@code null} for a message that shows no entry, such as the reason a turn failed
     */
    @Transactional
    public void delivered(long workspaceId, @Nullable UUID shows, String ts) {
        show(workspaceId, shows, ts);
    }

    /** As {@link #delivered(long, UUID, String)}, with the receipts it decides in the same transaction. */
    @Transactional
    public void delivered(long workspaceId, @Nullable UUID shows, String ts, Runnable receipts) {
        show(workspaceId, shows, ts);
        receipts.run();
    }

    private void show(long workspaceId, @Nullable UUID shows, String ts) {
        if (shows != null) {
            deliveries.setSlackTs(workspaceId, shows, ts);
        }
    }

    /**
     * Records the Slack stream that carries the reply to a question; {@code null} when the reply no longer goes to that
     * stream: a retried attempt starts a new one, or the reply is posted as a new message.
     */
    @Transactional
    public void streaming(long workspaceId, UUID questionId, @Nullable String streamTs) {
        deliveries.setSlackStreamTs(workspaceId, questionId, streamTs);
    }

    /**
     * Settles the reply to a Slack turn as failed when it can no longer reach Slack: the DM or its parent is gone, or
     * Slack is disconnected.
     *
     * @param receipts the receipts of the feedback the answer claimed, recorded in the same transaction when the turn
     *     exists
     */
    @Transactional
    public void giveUp(long workspaceId, UUID turnId, Runnable receipts) {
        if (turns.findByIdAndWorkspaceId(turnId, workspaceId).isPresent()) receipts.run();
    }
}
