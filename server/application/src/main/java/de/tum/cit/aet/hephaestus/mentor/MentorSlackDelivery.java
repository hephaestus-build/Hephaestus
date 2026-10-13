package de.tum.cit.aet.hephaestus.mentor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The ledger's part of projecting Slack turns into Slack: where a turn's reply goes, and the intent, effect and
 * outcome of every Slack post ({@link MentorEntry.SlackDelivery}).
 *
 * <p>A post first takes its intent ({@link #claim}: no state to {@code PENDING}, won by one caller only), then calls
 * Slack, then records the outcome ({@link #settle}). A crash between the call and the outcome leaves {@code PENDING};
 * the reconciler takes it again ({@link #retry}) and reads the Slack history to decide.
 *
 * <p>The question of a Slack turn carries the state of the whole reply to it, and the server that claims it projects
 * the turn. Heph's answer carries only the {@code ts} of the Slack message that shows it, so a Slack thread on that
 * message finds it. A weekly digest carries the state and {@code ts} of its own note.
 */
@Service
@RequiredArgsConstructor
public class MentorSlackDelivery {

    private final MentorConversationRepository conversations;
    private final MentorEntryRepository entries;
    private final MentorSlackDeliveryRepository deliveries;
    private final MentorTurnRepository turns;
    private final MentorTimeline timeline;

    /**
     * A turn asked in Slack, with where its reply goes.
     *
     * @param threadTs the Slack thread to reply in; {@code null} for the top level of the DM
     * @param messages Heph's stored messages of the turn as the app shows them, with the feedback each showed; none
     *     while the turn runs
     */
    public record SlackTurn(
            MentorTurn turn,
            String channelId,
            @Nullable String threadTs,
            MentorEntry question,
            List<MentorEntryDTO> messages) {

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
        return Optional.of(new SlackTurn(
                turn,
                channelId,
                threadTs,
                entries.findQuestion(turn.getWorkspaceId(), turn.getId()),
                timeline.shown(conversation.get(), stored)));
    }

    /** The turn as it is now, with its partial answer: one read for each streamed piece. */
    @Transactional(readOnly = true)
    public Optional<MentorTurn> turn(long workspaceId, UUID turnId) {
        return turns.findByIdAndWorkspaceId(turnId, workspaceId);
    }

    /**
     * What a Slack stream may show of a running turn's partial answer: a stream only grows, and the feedback after
     * Heph's first {@code link_observation} reaches it only with the stored answer.
     */
    public static String writing(MentorTurn turn) {
        JsonNode partial = turn.getPartial();
        return partial == null ? "" : MentorPiEntries.partialText(partial, true);
    }

    /** Takes the intent to post an entry. Only the first caller gets {@code true}. */
    @Transactional
    public boolean claim(long workspaceId, UUID entryId) {
        return deliveries.claimSlackDelivery(workspaceId, entryId) == 1;
    }

    /** Leaves a taken post whose outcome is unknown to the reconciler. */
    @Transactional
    public void markUncertain(long workspaceId, UUID entryId) {
        deliveries.markSlackDeliveryUncertain(workspaceId, entryId);
    }

    @Transactional
    public void settle(long workspaceId, UUID entryId, MentorEntry.SlackDelivery outcome) {
        deliveries.settleSlackDelivery(workspaceId, entryId, outcome);
    }

    /**
     * Settles a post that reached Slack.
     *
     * @param shows the entry that the Slack message shows and that takes its {@code ts}: a weekly digest, or Heph's
     *     answer; {@code null} for a message that shows no entry, such as the reason a turn failed
     */
    @Transactional
    public void delivered(long workspaceId, UUID entryId, @Nullable UUID shows, String ts) {
        deliveries.settleSlackDelivery(workspaceId, entryId, MentorEntry.SlackDelivery.DELIVERED);
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

    /** Settles the reply to a Slack turn as failed when it can no longer reach Slack: the DM or its parent is gone. */
    @Transactional
    public void giveUp(long workspaceId, UUID turnId) {
        turns.findByIdAndWorkspaceId(turnId, workspaceId)
                .map(turn -> entries.findQuestion(workspaceId, turn.getId()))
                .ifPresent(question -> deliveries.settleSlackDelivery(
                        workspaceId, question.getId(), MentorEntry.SlackDelivery.FAILED));
    }

    /** Takes an unsettled post for one more try. {@code false} when it was settled meanwhile. */
    @Transactional
    public boolean retry(long workspaceId, UUID entryId) {
        return deliveries.retrySlackDelivery(workspaceId, entryId) == 1;
    }

    /**
     * An unsettled Slack post: a Slack turn's reply, on its question, or a weekly digest's note.
     *
     * @param turnId the turn whose reply it is; {@code null} for a weekly digest
     */
    public record Unsettled(
            long workspaceId, UUID entryId, @Nullable UUID turnId) {}

    /**
     * The unsettled Slack posts that are due, after settling as failed the ones tried {@code maxAttempts} times. See
     * {@link MentorSlackDeliveryRepository#findDueSlackDeliveries} for the order and the backoff.
     */
    @Transactional
    public List<Unsettled> due(
            Instant floor, Instant now, Duration delay, int maxAttempts, int perWorkspace, int limit) {
        deliveries.failExhaustedSlackDeliveries(maxAttempts);
        return deliveries.findDueSlackDeliveries(floor, now, delay.toMillis() / 1000.0, perWorkspace, limit).stream()
                .map(row -> new Unsettled(row.getWorkspaceId(), row.getEntryId(), row.getTurnId()))
                .toList();
    }
}
