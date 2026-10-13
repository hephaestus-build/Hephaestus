package de.tum.cit.aet.hephaestus.integration.slack.events;

import com.slack.api.model.block.LayoutBlock;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorAdmission;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorReadinessQuery;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorRefusal;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorPosts;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackOnboardingService;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorRejectedException;
import de.tum.cit.aet.hephaestus.mentor.MentorTimeline;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Slack ingress of Heph: what the developer does in their DM with the app becomes a change to their Heph
 * conversation. A top-level DM is a question in the main conversation; a reply in a Slack thread is a question in
 * the Heph thread on the thread's parent message.
 *
 * <p>A top-level question finds the developer's main conversation by the developer. A thread reply, an edit, a
 * deletion, a stop and a vote need the conversation that this exact Slack DM (team, member, channel) belongs to in the
 * workspace; a Slack message of another workspace or another member never matches.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackMentorService {

    private static final Logger log = LoggerFactory.getLogger(SlackMentorService.class);

    static final String NOT_LINKED =
            "Heph cannot reply yet. Check that your Hephaestus account is active, linked to Slack, and a member of this workspace.";

    private final SlackWorkspaceResolver workspaceResolver;
    private final SlackMessageService slackMessageService;
    private final SlackMentorIdentityResolver identityResolver;
    private final SlackMentorInputGuard inputGuard;
    private final SlackOnboardingService onboardingService;
    private final MentorReadinessQuery mentorReadinessQuery;
    private final MentorAdmission mentorAdmission;
    private final MentorConversationService conversations;
    private final MentorEntryService entries;
    private final MentorTurnService turns;
    private final MentorTimeline timeline;

    public SlackMentorService(
            SlackWorkspaceResolver workspaceResolver,
            SlackMessageService slackMessageService,
            SlackMentorIdentityResolver identityResolver,
            SlackMentorInputGuard inputGuard,
            SlackOnboardingService onboardingService,
            MentorReadinessQuery mentorReadinessQuery,
            MentorAdmission mentorAdmission,
            MentorConversationService conversations,
            MentorEntryService entries,
            MentorTurnService turns,
            MentorTimeline timeline) {
        this.workspaceResolver = workspaceResolver;
        this.slackMessageService = slackMessageService;
        this.identityResolver = identityResolver;
        this.inputGuard = inputGuard;
        this.onboardingService = onboardingService;
        this.mentorReadinessQuery = mentorReadinessQuery;
        this.mentorAdmission = mentorAdmission;
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
        if (text.isBlank()) {
            return;
        }
        Optional<Long> workspaceOpt = workspaceResolver.resolveWorkspaceId(teamId);
        if (workspaceOpt.isEmpty()) {
            log.warn("Slack DM for unknown/inactive team {} — no workspace connection", teamId);
            return;
        }
        long workspaceId = workspaceOpt.get();
        if (!mentorReadinessQuery.isReady(workspaceId)) {
            log.debug("Heph is not ready for workspace={}, ignoring DM", workspaceId);
            return;
        }
        String replyTs = threadTs.equals(messageTs) ? null : threadTs;
        SlackMentorInputGuard.Verdict verdict = inputGuard.decide(text);
        if (!verdict.allowsMentorTurn()) {
            if (verdict.responseText() != null && !verdict.responseText().isBlank()) {
                reply(workspaceId, channelId, replyTs, verdict.responseText());
            }
            log.info("Slack DM diverted by input guard: workspace={} action={}", workspaceId, verdict.action());
            return;
        }
        Optional<SlackMentorIdentityResolver.Member> member =
                identityResolver.resolveActiveMember(workspaceId, teamId, slackUserId);
        if (member.isEmpty()) {
            send(workspaceId, channelId, replyTs, onboardingService.linkCtaBlocks(), NOT_LINKED);
            return;
        }
        long developerId = member.get().actorId();
        List<Long> accountActorIds = member.get().accountActorIds();
        Optional<MentorRefusal> refusal = mentorAdmission.refusal(workspaceId, developerId);
        if (refusal.isPresent()) {
            reply(workspaceId, channelId, replyTs, refusal.get().userMessage());
            return;
        }
        try {
            // A disconnect either commits first, and nothing is stored, or waits and then erases what this stored.
            Optional<MentorTurnService.Submission> accepted =
                    workspaceResolver.whileConnected(workspaceId, teamId, () -> {
                        UUID conversationId = replyTs == null
                                ? topLevel(workspaceId, developerId, accountActorIds, teamId, slackUserId, channelId)
                                : thread(workspaceId, accountActorIds, teamId, slackUserId, channelId, replyTs);
                        return turns.submit(
                                workspaceId,
                                conversationId,
                                new MentorTurnService.Question(
                                        MentorTurn.Surface.SLACK,
                                        text,
                                        requestId(teamId, channelId, messageTs),
                                        messageTs,
                                        null));
                    });
            if (accepted.isEmpty()) {
                log.info(
                        "Slack DM for workspace {} arrived while its Slack connection ended; stored nothing",
                        workspaceId);
                return;
            }
            MentorTurnService.Submission submission = accepted.get();
            log.info(
                    "Accepted Slack Heph question: workspace={} conversation={} turn={} new={}",
                    workspaceId,
                    submission.turn().getConversationId(),
                    submission.turn().getId(),
                    submission.created());
        } catch (MentorRejectedException rejected) {
            reply(workspaceId, channelId, replyTs, rejected.reason().userMessage());
        }
    }

    private UUID topLevel(
            long workspaceId,
            long developerId,
            List<Long> accountActorIds,
            String teamId,
            String slackUserId,
            String channelId) {
        MentorConversation main = conversations.findOrCreateMain(workspaceId, developerId, accountActorIds);
        conversations.bindSlackIdentity(workspaceId, main.getId(), teamId, slackUserId, channelId);
        return main.getId();
    }

    /**
     * The Heph thread on the Slack thread's parent, which must be a message of this DM's conversation. Any other parent
     * is refused with {@code NOT_A_THREAD_PARENT}.
     */
    private UUID thread(
            long workspaceId,
            List<Long> accountActorIds,
            String teamId,
            String slackUserId,
            String channelId,
            String parentTs) {
        MentorEntry parent = conversations
                .findMainBySlack(workspaceId, teamId, slackUserId, channelId)
                .filter(main -> accountActorIds.contains(main.getUserId()))
                .flatMap(main -> entries.findBySlackTs(workspaceId, main.getId(), parentTs))
                .orElseThrow(() -> new MentorRejectedException(MentorRejectedException.Reason.NOT_A_THREAD_PARENT));
        return conversations.openThread(workspaceId, parent.getId()).getId();
    }

    /** The developer edited a DM: the ledger keeps the new text and marks it edited. */
    public void handleEdit(String teamId, String channelId, String slackUserId, String messageTs, String text) {
        ownMessage(teamId, channelId, slackUserId, messageTs).ifPresent(found -> {
            MentorEntry entry = found.entry();
            if (entry.getKind() == MentorEntry.Kind.USER && !entry.isRedacted() && !text.isBlank()) {
                entries.editSlackMessage(found.workspaceId(), entry.getConversationId(), messageTs, text);
            }
        });
    }

    /** The developer deleted a DM: the ledger erases it and stops Heph's work on it. */
    public void handleDelete(String teamId, String channelId, String slackUserId, String deletedTs) {
        ownMessage(teamId, channelId, slackUserId, deletedTs).ifPresent(found -> {
            if (found.entry().getKind() == MentorEntry.Kind.USER) {
                entries.deleteMessage(found.workspaceId(), found.entry().getId());
            }
        });
    }

    /** The developer pressed Helpful or Not helpful under one of Heph's answers. */
    public void handleFeedback(String teamId, String channelId, String slackUserId, String value) {
        Optional<SlackMentorPosts.Feedback> feedback = SlackMentorPosts.feedback(value);
        Optional<Long> workspaceOpt = workspaceResolver.resolveWorkspaceId(teamId);
        if (feedback.isEmpty() || workspaceOpt.isEmpty()) {
            return;
        }
        long workspaceId = workspaceOpt.get();
        Optional<MentorConversation> main = conversations.findMainBySlack(workspaceId, teamId, slackUserId, channelId);
        if (main.isEmpty()) {
            return;
        }
        UUID entryId = feedback.get().entryId();
        try {
            timeline.requireOwnedEntry(workspaceId, Set.of(main.get().getUserId()), entryId);
            entries.vote(workspaceId, entryId, feedback.get().vote());
        } catch (EntityNotFoundException | MentorRejectedException refused) {
            log.debug("Slack feedback on entry {} not recorded: {}", entryId, refused.getMessage());
        }
    }

    private record OwnMessage(long workspaceId, MentorEntry entry) {}

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
                .filter(entry -> entry.getSurface() == MentorEntry.Surface.SLACK)
                .map(entry -> new OwnMessage(workspaceId, entry));
    }

    /** The idempotency key of a Slack message: the same message always submits the same question. */
    static String requestId(String teamId, String channelId, String messageTs) {
        return "slack:" + teamId + ":" + channelId + ":" + messageTs;
    }

    private void reply(long workspaceId, String channelId, @Nullable String threadTs, String text) {
        send(workspaceId, channelId, threadTs, List.of(), text);
    }

    private void send(
            long workspaceId, String channelId, @Nullable String threadTs, List<LayoutBlock> blocks, String text) {
        try {
            slackMessageService.postMessage(
                    workspaceId, channelId, threadTs, blocks, text, SlackMentorPosts.noticeMetadata());
        } catch (SlackSendException e) {
            log.warn("Slack reply to a Heph DM failed: workspace={}, error={}", workspaceId, e.slackError());
        }
    }
}
