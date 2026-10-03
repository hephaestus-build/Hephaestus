package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import static com.slack.api.model.block.Blocks.asBlocks;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.agent.handler.conversation.ConversationFeedbackPreparedEvent;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorReadinessQuery;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorRefusal;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorTurnRunner;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Notifies a recipient that conversational feedback is ready without exposing what it says. */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackConversationNudgeService {

    private static final Logger log = LoggerFactory.getLogger(SlackConversationNudgeService.class);

    private static final Duration COOLDOWN = Duration.ofHours(24);
    private static final int MAX_COOLDOWN_ENTRIES = 10_000;

    private final ConnectionService connectionService;
    private final AccountPreferencesQuery accountPreferencesQuery;
    private final SlackMentorIdentityResolver identityResolver;
    private final SlackMessageService slackMessageService;
    private final MentorReadinessQuery mentorReadinessQuery;
    private final MentorTurnRunner mentorTurnRunner;

    private final Cache<Recipient, Instant> cooldowns = Caffeine.newBuilder()
            .maximumSize(MAX_COOLDOWN_ENTRIES)
            .expireAfterWrite(COOLDOWN)
            .build();

    public SlackConversationNudgeService(
            ConnectionService connectionService,
            AccountPreferencesQuery accountPreferencesQuery,
            SlackMentorIdentityResolver identityResolver,
            SlackMessageService slackMessageService,
            MentorReadinessQuery mentorReadinessQuery,
            MentorTurnRunner mentorTurnRunner) {
        this.connectionService = connectionService;
        this.accountPreferencesQuery = accountPreferencesQuery;
        this.identityResolver = identityResolver;
        this.slackMessageService = slackMessageService;
        this.mentorReadinessQuery = mentorReadinessQuery;
        this.mentorTurnRunner = mentorTurnRunner;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConversationFeedbackPrepared(ConversationFeedbackPreparedEvent event) {
        try {
            nudge(event);
        } catch (RuntimeException e) {
            log.warn(
                    "slack.nudge: failed for workspaceId={}, recipientUserId={}: {}",
                    event.workspaceId(),
                    event.recipientUserId(),
                    e.toString());
        }
    }

    private void nudge(ConversationFeedbackPreparedEvent event) {
        if (event.unitCount() <= 0 || event.workspaceId() == null || event.recipientUserId() == null) {
            return;
        }
        long workspaceId = event.workspaceId();
        long recipientId = event.recipientUserId();
        if (!mentorReadinessQuery.isReady(workspaceId)) {
            log.debug("slack.nudge: skip, mentor unavailable: workspaceId={}", workspaceId);
            return;
        }
        Optional<Connection> connection = connectionService.findActive(workspaceId, IntegrationKind.SLACK);
        if (connection.isEmpty()) {
            log.debug("slack.nudge: skip, no ACTIVE Slack connection: workspaceId={}", workspaceId);
            return;
        }
        boolean practiceFeedbackDeliveryEnabled = accountPreferencesQuery.practiceFeedbackDeliveryEnabled(recipientId);
        if (!practiceFeedbackDeliveryEnabled) {
            log.debug("slack.nudge: skip, feedback delivery disabled: recipientUserId={}", recipientId);
            return;
        }
        Optional<String> slackUserId = identityResolver.resolveSlackUserId(
                recipientId, connection.get().getInstanceKey());
        if (slackUserId.isEmpty()) {
            log.debug("slack.nudge: skip, no Slack identity link: recipientUserId={}", recipientId);
            return;
        }
        // Eligibility can change while the notification event waits in the async queue.
        Optional<MentorRefusal> refusal = mentorTurnRunner.refusal(workspaceId, recipientId);
        if (refusal.isPresent()) {
            log.debug(
                    "slack.nudge: skip, Heph declines the recipient: recipientUserId={}, refusal={}",
                    recipientId,
                    refusal.get());
            return;
        }
        Recipient recipient = new Recipient(workspaceId, recipientId);
        Instant now = Instant.now();
        if (!claimWindow(recipient, now)) {
            log.debug("slack.nudge: skip, on cooldown: recipientUserId={}", recipientId);
            return;
        }
        String text = message(event.unitCount());
        try {
            slackMessageService.sendForWorkspace(
                    workspaceId, slackUserId.get(), asBlocks(section(s -> s.text(markdownText(text)))), text);
        } catch (SlackSendException e) {
            cooldowns.asMap().remove(recipient, now);
            log.warn(
                    "slack.nudge: send failed: workspaceId={}, recipientUserId={}, slackError={}",
                    workspaceId,
                    recipientId,
                    e.slackError());
        }
    }

    private boolean claimWindow(Recipient recipient, Instant now) {
        return cooldowns.asMap().putIfAbsent(recipient, now) == null;
    }

    /** Count-only copy — deliberately no observation details, no severity, no artifact references. */
    static String message(int unitCount) {
        return unitCount == 1
                ? "You have 1 new practice observation to explore — reply here to go through it."
                : "You have " + unitCount + " new practice observations to explore — reply here to go through them.";
    }

    private record Recipient(long workspaceId, long userId) {}
}
