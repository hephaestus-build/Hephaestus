package de.tum.cit.aet.hephaestus.integration.slack.events;

import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationAdmission;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationRefusal;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationRejectedException;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTimeline;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.TransientFailures;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.consumer.DeliveryInbox;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationPosts;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackNotice;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackNoticeActions;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackInteractiveReplyAdapter;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMembershipUnavailableException;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationReadinessQuery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Slack ingress of Hephaestus: what the developer does in their DM with the app becomes a change to their Hephaestus
 * conversation. A top-level DM is a question in the main conversation; a reply in a Slack thread is a question in
 * the Hephaestus thread on the thread's parent message.
 *
 * <p>A top-level question finds the developer's main conversation by the developer. A thread reply, an edit, a
 * deletion and a vote need the conversation that this exact Slack DM (team, member, channel) belongs to in the
 * workspace; a Slack message of another workspace or another member never matches.
 */
@Service
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackConversationService {

    private static final Logger log = LoggerFactory.getLogger(SlackConversationService.class);
    private static final String SUBMISSION_RECEIPT = "slack.conversation.submit";
    private static final String MEMBERSHIP_REPLY_RECEIPT = "slack.conversation.membership-reply";

    static final String NOT_LINKED =
            "Hephaestus cannot reply yet. Check that your Hephaestus account is active, linked to Slack, and a member of this workspace.";
    static final String SLACK_MEMBERSHIP_REQUIRED =
            "Hephaestus can reply only if Slack confirms that you’re a full member of this Slack workspace.";
    static final String SLACK_MEMBERSHIP_UNAVAILABLE =
            "Hephaestus couldn’t verify your Slack workspace membership. Ask an admin to check the Slack connection.";

    private final SlackWorkspaceResolver workspaceResolver;
    private final SlackMessageService slack;
    private final SlackInteractiveReplyAdapter interactiveReplies;
    private final SlackNoticeActions notices;
    private final SlackConversationIdentityResolver identityResolver;
    private final SlackConversationInputGuard inputGuard;
    private final ConversationReadinessQuery conversationReadinessQuery;
    private final ConversationAdmission conversationAdmission;
    private final ConversationService conversations;
    private final ConversationEntryService entries;
    private final ConversationTurnService turns;
    private final ConversationTimeline timeline;

    public SlackConversationService(
            SlackWorkspaceResolver workspaceResolver,
            SlackMessageService slack,
            SlackInteractiveReplyAdapter interactiveReplies,
            SlackNoticeActions notices,
            SlackConversationIdentityResolver identityResolver,
            SlackConversationInputGuard inputGuard,
            ConversationReadinessQuery conversationReadinessQuery,
            ConversationAdmission conversationAdmission,
            ConversationService conversations,
            ConversationEntryService entries,
            ConversationTurnService turns,
            ConversationTimeline timeline) {
        this.workspaceResolver = workspaceResolver;
        this.slack = slack;
        this.interactiveReplies = interactiveReplies;
        this.notices = notices;
        this.identityResolver = identityResolver;
        this.inputGuard = inputGuard;
        this.conversationReadinessQuery = conversationReadinessQuery;
        this.conversationAdmission = conversationAdmission;
        this.conversations = conversations;
        this.entries = entries;
        this.turns = turns;
        this.timeline = timeline;
    }

    /**
     * A message the developer wrote in the DM.
     *
     * @param threadTs the Slack thread's parent {@code ts}; equal to {@code messageTs} for a top-level message
     */
    public void handleDm(
            String teamId, String channelId, String slackUserId, String text, String messageTs, String threadTs) {
        if (text.isBlank() || slackUserId.isBlank() || DeliveryInbox.completed(SUBMISSION_RECEIPT)) {
            return;
        }
        Optional<Long> workspaceOpt = workspaceResolver.resolveWorkspaceId(teamId);
        if (workspaceOpt.isEmpty()) {
            log.warn("Slack DM for unknown/inactive team {} — no workspace connection", teamId);
            return;
        }
        long workspaceId = workspaceOpt.get();
        String replyTs = threadTs.equals(messageTs) ? null : threadTs;
        SlackConversationInputGuard.Verdict verdict = inputGuard.decide(text);
        if (!verdict.allowsConversationTurn()) {
            if (verdict.responseText() != null && !verdict.responseText().isBlank()) {
                reply(
                        workspaceId,
                        teamId,
                        slackUserId,
                        channelId,
                        replyTs,
                        messageTs,
                        verdict.responseText(),
                        false,
                        null);
            }
            log.info("Slack DM diverted by input guard: workspace={} action={}", workspaceId, verdict.action());
            return;
        }
        if (!checkMembership(workspaceId, teamId, channelId, slackUserId)) {
            return;
        }
        if (!conversationReadinessQuery.isReady(workspaceId)) {
            reply(
                    workspaceId,
                    teamId,
                    slackUserId,
                    channelId,
                    replyTs,
                    messageTs,
                    ConversationRefusal.UNAVAILABLE.userMessage(),
                    false,
                    null);
            return;
        }
        Optional<Long> account = identityResolver.resolveActiveAccountId(workspaceId, teamId, slackUserId);
        if (account.isEmpty()) {
            reply(workspaceId, teamId, slackUserId, channelId, replyTs, messageTs, NOT_LINKED, true, null);
            return;
        }
        long accountId = account.get();
        Optional<ConversationRefusal> refusal = conversationAdmission.refusal(workspaceId, accountId);
        if (refusal.isPresent()) {
            reply(
                    workspaceId,
                    teamId,
                    slackUserId,
                    channelId,
                    replyTs,
                    messageTs,
                    refusal.get().userMessage(),
                    false,
                    accountId);
            return;
        }
        try {
            // A disconnect either commits first, and nothing is stored, or waits and then erases what this stored.
            Optional<Boolean> accepted = workspaceResolver.whileConnected(workspaceId, teamId, () -> {
                if (!DeliveryInbox.claim(SUBMISSION_RECEIPT)) return false;
                UUID conversationId = replyTs == null
                        ? topLevel(workspaceId, accountId, teamId, slackUserId, channelId)
                        : thread(workspaceId, accountId, teamId, slackUserId, channelId, replyTs);
                ConversationTurnService.Submission submission = turns.submit(
                        workspaceId,
                        conversationId,
                        new ConversationTurnService.Question(
                                ConversationTurn.Surface.SLACK,
                                text,
                                requestId(teamId, channelId, messageTs),
                                messageTs,
                                null));
                log.info(
                        "Accepted Slack Hephaestus question: workspace={} conversation={} turn={} new={}",
                        workspaceId,
                        submission.turn().getConversationId(),
                        submission.turn().getId(),
                        submission.created());
                return true;
            });
            if (accepted.isEmpty()) {
                log.info(
                        "Slack DM for workspace {} arrived while its Slack connection ended; stored nothing",
                        workspaceId);
            }
        } catch (ConversationRejectedException rejected) {
            reply(
                    workspaceId,
                    teamId,
                    slackUserId,
                    channelId,
                    replyTs,
                    messageTs,
                    rejected.reason().userMessage(),
                    false,
                    accountId);
        }
    }

    private UUID topLevel(long workspaceId, long accountId, String teamId, String slackUserId, String channelId) {
        Conversation main = conversations.findOrCreateMain(workspaceId, accountId);
        conversations.bindSlackIdentity(workspaceId, main.getId(), teamId, slackUserId, channelId);
        return main.getId();
    }

    /**
     * The Hephaestus thread on the Slack thread's parent, which must be a message of this DM's conversation. Any other parent
     * is refused with {@code NOT_A_THREAD_PARENT}.
     */
    private UUID thread(
            long workspaceId, long accountId, String teamId, String slackUserId, String channelId, String parentTs) {
        ConversationEntry parent = conversations
                .findMainBySlack(workspaceId, teamId, slackUserId, channelId)
                .filter(main -> main.getAccountId() == accountId)
                .flatMap(main -> entries.findBySlackTs(workspaceId, main.getId(), parentTs))
                .orElseThrow(() ->
                        new ConversationRejectedException(ConversationRejectedException.Reason.NOT_A_THREAD_PARENT));
        return conversations.openThread(workspaceId, parent.getId()).getId();
    }

    /** The developer edited a DM: the ledger keeps the new text and marks it edited. */
    public void handleEdit(String teamId, String channelId, String slackUserId, String messageTs, String text) {
        ownMessage(teamId, channelId, slackUserId, messageTs).ifPresent(found -> {
            ConversationEntry entry = found.entry();
            if (entry.getKind() == ConversationEntry.Kind.USER && !entry.isRedacted() && !text.isBlank()) {
                workspaceResolver.whileConnected(found.workspaceId(), teamId, () -> {
                    if (DeliveryInbox.claim("slack.conversation.edit")) {
                        entries.editSlackMessage(found.workspaceId(), entry.getConversationId(), messageTs, text);
                    }
                    return true;
                });
            }
        });
    }

    /** The developer deleted a DM: the ledger erases it and stops Hephaestus's work on it. */
    public void handleDelete(String teamId, String channelId, String slackUserId, String deletedTs) {
        ownMessage(teamId, channelId, slackUserId, deletedTs).ifPresent(found -> {
            if (found.entry().getKind() == ConversationEntry.Kind.USER) {
                workspaceResolver.whileConnected(found.workspaceId(), teamId, () -> {
                    if (DeliveryInbox.claim("slack.conversation.delete")) {
                        entries.deleteMessage(found.workspaceId(), found.entry().getId());
                    }
                    return true;
                });
            }
        });
    }

    /** The developer pressed Helpful or Not helpful under one of Hephaestus's answers. */
    public void handleFeedback(String teamId, String channelId, String slackUserId, String value) {
        Optional<SlackConversationPosts.Feedback> feedback = SlackConversationPosts.feedback(value);
        Optional<Long> workspaceOpt = workspaceResolver.resolveWorkspaceId(teamId);
        if (feedback.isEmpty() || workspaceOpt.isEmpty()) {
            return;
        }
        long workspaceId = workspaceOpt.get();
        Optional<Conversation> main = conversations.findMainBySlack(workspaceId, teamId, slackUserId, channelId);
        if (main.isEmpty()) {
            return;
        }
        UUID entryId = feedback.get().entryId();
        try {
            timeline.requireOwnedEntry(workspaceId, main.get().getAccountId(), entryId);
            entries.vote(workspaceId, entryId, feedback.get().vote());
        } catch (EntityNotFoundException | ConversationRejectedException refused) {
            log.debug(
                    "Slack feedback on entry {} not recorded: {}",
                    entryId,
                    refused instanceof ConversationRejectedException rejected
                            ? rejected.reason()
                            : refused.getClass().getSimpleName());
        }
    }

    private record OwnMessage(long workspaceId, ConversationEntry entry) {}

    /** A message of the DM's own conversation, found by its Slack {@code ts}. */
    private Optional<OwnMessage> ownMessage(String teamId, String channelId, String slackUserId, String ts) {
        Optional<Long> workspaceOpt = workspaceResolver.resolveWorkspaceId(teamId);
        if (workspaceOpt.isEmpty() || slackUserId.isBlank() || ts.isBlank()) {
            return Optional.empty();
        }
        long workspaceId = workspaceOpt.get();
        return conversations
                .findMainBySlack(workspaceId, teamId, slackUserId, channelId)
                .flatMap(main -> entries.findSlackMessage(workspaceId, main.getId(), ts))
                .filter(entry -> entry.getSurface() == ConversationEntry.Surface.SLACK)
                .map(entry -> new OwnMessage(workspaceId, entry));
    }

    /** The idempotency key of a Slack message: the same message always submits the same question. */
    static String requestId(String teamId, String channelId, String messageTs) {
        return "slack:" + teamId + ":" + channelId + ":" + messageTs;
    }

    private boolean checkMembership(long workspaceId, String teamId, String channelId, String slackUserId) {
        try {
            if (slack.isCurrentFullMember(workspaceId, teamId, slackUserId)) return true;
        } catch (SlackMembershipUnavailableException unavailable) {
            if (DeliveryInbox.isLastCountedAttempt() && !TransientFailures.isTransient(unavailable)) {
                // Keep submission open so an operator can replay the question after the connection is fixed.
                refuseMembership(workspaceId, teamId, channelId, slackUserId, SLACK_MEMBERSHIP_UNAVAILABLE, false);
            }
            throw unavailable;
        }
        refuseMembership(workspaceId, teamId, channelId, slackUserId, SLACK_MEMBERSHIP_REQUIRED, true);
        return false;
    }

    private void refuseMembership(
            long workspaceId,
            String teamId,
            String channelId,
            String slackUserId,
            String text,
            boolean closesSubmission) {
        // Slack's ephemeral replies have no readback. Commit the receipt before sending, so a replay never repeats it.
        if (!workspaceResolver
                .whileConnected(
                        workspaceId,
                        teamId,
                        () -> (!closesSubmission || DeliveryInbox.claim(SUBMISSION_RECEIPT))
                                && DeliveryInbox.claim(MEMBERSHIP_REPLY_RECEIPT))
                .orElse(false)) {
            return;
        }
        try {
            interactiveReplies.sendEphemeralForWorkspace(workspaceId, channelId, slackUserId, List.of(), text);
        } catch (SlackSendException failed) {
            log.warn("Slack DM membership refusal could not be posted: workspace={}", workspaceId);
        }
    }

    private void reply(
            long workspaceId,
            String teamId,
            String slackUserId,
            String channelId,
            @Nullable String threadTs,
            String messageTs,
            String text,
            boolean linkAccount,
            @Nullable Long accountId) {
        workspaceResolver.whileConnected(workspaceId, teamId, () -> {
            if (!DeliveryInbox.claim(SUBMISSION_RECEIPT)) return false;
            notices.reply(
                    workspaceId,
                    teamId,
                    slackUserId,
                    accountId,
                    new SlackNotice(channelId, threadTs, messageTs, text, linkAccount));
            return true;
        });
    }
}
