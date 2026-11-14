package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import com.slack.api.model.block.LayoutBlock;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionEnded;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.action.ActionVariants;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ModelLinkHosts;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackMessages;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackMessages.SlackTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackReplies;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** One completed Slack message per ledger entry, fenced and settled by the outbox executor. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class SlackPostAdapter implements ActionAdapter {
    private static final Set<String> UNKNOWN_EFFECT = Set.of(
            "transport_failure",
            "invalid_receipt",
            "internal_error",
            "fatal_error",
            "service_unavailable",
            "request_timeout",
            "unknown");
    private final ObjectProvider<SlackMessageService> slack;
    private final ObjectProvider<SlackWeeklyDigestPointer> digests;
    private final MentorSlackReplies replies;
    private final MentorSlackMessages messages;
    private final MentorTurnCompletion completion;
    private final ModelLinkHosts linkHosts;
    private final ObjectMapper mapper;

    record Recipient(String teamId, String userId) {}

    record Post(
            String channelId,
            @Nullable String threadTs,
            List<LayoutBlock> blocks,
            String text,
            String oldestTs,
            @Nullable Recipient recipient) {}

    record Receipt(String channelId, String ts, @Nullable Recipient recipient) {}

    @Override
    public ActionKind kind() {
        return ActionKind.SLACK_POST;
    }

    @Override
    public ActionVariants variants() {
        return ActionVariants.one(MentorSlackReplies.Payload.class);
    }

    @Override
    public ActionResult execute(ActionAttempt attempt) {
        if (slack.getIfAvailable() == null) return unavailable(attempt, ActionStopReason.NOT_CONFIGURED);
        var payload = replies.payload(attempt);
        if (payload.turnId() == null) {
            var notes = digests.getIfAvailable();
            return notes == null
                    ? unavailable(attempt, ActionStopReason.NOT_CONFIGURED)
                    : notes.attempt(attempt, payload);
        }
        long workspace = Objects.requireNonNull(attempt.workspaceId());
        var found = messages.slackTurn(workspace, payload.turnId());
        if (found.isEmpty() || found.get().question().isRedacted())
            return unavailable(attempt, ActionStopReason.WITHDRAWN);
        var turn = found.get();
        if (!turn.turn().getStatus().isTerminal()) return new ActionResult.Defer(Duration.ofSeconds(1));
        if (turn.turn().getStatus() == MentorTurn.Status.STOPPED)
            return unavailable(attempt, ActionStopReason.WITHDRAWN);
        var reply = reply(turn);
        boolean answered = turn.turn().getStatus() == MentorTurn.Status.DONE
                && !reply.text().isBlank();
        String text = answered
                ? reply.text()
                : SlackMentorPosts.failedReply(turn.turn().getError());
        var footer = answered && turn.answerId() != null
                ? SlackMentorPosts.answerFooter(turn.answerId(), turn.firstAnswer(), turn.inThread())
                : List.<LayoutBlock>of();
        var blocks = Stream.concat(SlackMentorPosts.markdown(text).stream(), footer.stream())
                .toList();
        String fallback = SlackMentorPosts.notification(text)
                + (answered && turn.firstAnswer() ? " " + SlackMentorPosts.AI_LABEL : "");
        return post(
                attempt,
                payload.entryId(),
                new Post(
                        turn.channelId(),
                        turn.threadTs(),
                        blocks,
                        fallback,
                        SlackTs.ofInstant(turn.question().getCreatedAt()),
                        null));
    }

    /** A write that may have reached Slack is only read back, never posted a second time. */
    ActionResult post(ActionAttempt attempt, UUID marker, Post post) {
        var client = slack.getIfAvailable();
        long workspace = Objects.requireNonNull(attempt.workspaceId());
        if (client == null || !client.isConnected(workspace))
            return unavailable(attempt, ActionStopReason.RECIPIENT_UNAVAILABLE);
        if (!attempt.fence().beginWrite()) return new ActionResult.Abandoned();
        if (attempt.writeStarted()) {
            try {
                var found = client.readWorkspaceMessages(
                        workspace,
                        post.channelId(),
                        post.threadTs(),
                        post.oldestTs(),
                        message -> SlackMentorPosts.isPostOf(message, marker));
                if (!found.ts().isEmpty())
                    return done(new Receipt(post.channelId(), found.ts().getFirst(), post.recipient()));
                if (found.more()) return new ActionResult.Reread(MentorSlackReplies.REREAD);
                return new ActionResult.Failed(ActionStopReason.REJECTED);
            } catch (SlackSendException unreadable) {
                return new ActionResult.Reread(MentorSlackReplies.REREAD);
            }
        }
        try {
            var posted = client.postMessage(
                    workspace,
                    post.channelId(),
                    post.threadTs(),
                    post.blocks(),
                    post.text(),
                    SlackMentorPosts.metadata(marker));
            return done(new Receipt(posted.channelId(), posted.ts(), post.recipient()));
        } catch (SlackSendException failed) {
            if ("silent_mode_engaged".equals(failed.slackError()))
                return new ActionResult.Cancelled(ActionStopReason.SILENCED);
            // A 429 proves no post was accepted; transport and invalid receipts prove no absence.
            if (failed.isRateLimited())
                return new ActionResult.Defer(
                        Duration.ofMillis(Math.max(MentorSlackReplies.REREAD.toMillis(), failed.retryAfterMillis())));
            return UNKNOWN_EFFECT.contains(failed.slackError())
                    ? new ActionResult.Reread(MentorSlackReplies.REREAD)
                    : new ActionResult.Failed(ActionStopReason.REJECTED);
        }
    }

    /** A lost recipient cannot prove that a previous write was absent. */
    static ActionResult unavailable(ActionAttempt attempt, ActionStopReason reason) {
        return attempt.writeStarted()
                ? new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED)
                : new ActionResult.Cancelled(reason);
    }

    private ActionResult.Done done(Receipt receipt) {
        return new ActionResult.Done(mapper.writeValueAsString(receipt));
    }

    private SlackMentorPosts.Reply reply(SlackTurn turn) {
        return SlackMentorPosts.reply(
                turn.messages(), linkHosts.forWorkspace(turn.turn().getWorkspaceId()));
    }

    /** Provider references and feedback receipts commit only when the action's fenced end commits. */
    @Override
    public void ended(ActionEnded ended) {
        settle(ended);
        replies.forgetRedacted(
                Objects.requireNonNull(ended.workspaceId()),
                List.of(MentorSlackReplies.entryOf(ended.idempotencyKey())));
    }

    private void settle(ActionEnded ended) {
        long workspace = Objects.requireNonNull(ended.workspaceId());
        UUID entry = MentorSlackReplies.entryOf(ended.idempotencyKey());
        Receipt receipt = ended.state() == ActionState.DONE && ended.readbackRef() != null
                ? mapper.readValue(ended.readbackRef(), Receipt.class)
                : null;
        if (ended.idempotencyKey().startsWith("slack_digest:")) {
            if (receipt != null) {
                var notes = digests.getIfAvailable();
                if (notes != null && notes.confirmed(workspace, entry, receipt))
                    messages.link(workspace, entry, receipt.ts());
            }
            return;
        }
        messages.turnForQuestion(workspace, entry).ifPresent(turn -> {
            if (turn.getStatus() != MentorTurn.Status.DONE) return;
            if (receipt != null) {
                var delivered = messages.slackTurn(workspace, turn.getId());
                if (delivered.isEmpty()) {
                    completion.recordUncertain(workspace, turn.getId());
                    return;
                }
                var answer = delivered.get();
                var reply = reply(answer);
                if (reply.text().isBlank()) {
                    completion.recordDelivery(workspace, turn.getId(), Set.of(), Set.of());
                    return;
                }
                if (answer.answerId() != null) messages.link(workspace, answer.answerId(), receipt.ts());
                var seen = reply.shownIn(reply.text().substring(0, SlackMentorPosts.shownLength(reply.text())));
                completion.recordDelivery(workspace, turn.getId(), seen.whole(), seen.partly());
            } else if (ended.state() == ActionState.UNCERTAIN) completion.recordUncertain(workspace, turn.getId());
            else if (ended.reason() == ActionStopReason.SILENCED) completion.recordSilenced(workspace, turn.getId());
            else completion.recordDelivery(workspace, turn.getId(), Set.of(), Set.of());
        });
    }
}
