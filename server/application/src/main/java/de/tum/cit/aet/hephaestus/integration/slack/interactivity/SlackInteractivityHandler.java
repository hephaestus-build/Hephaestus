package de.tum.cit.aet.hephaestus.integration.slack.interactivity;

import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;

import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.WeeklyDigestSubscriptions;
import de.tum.cit.aet.hephaestus.core.event.ContentFree;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackConsentBlocks;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationPosts;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackWeeklyDigestPointer;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackConversationService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackParticipantConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackWorkspaceResolver;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackInteractiveReplyAdapter;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService.EphemeralMessage;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackAppHomeService;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.JsonNode;

/**
 * Routes verified Slack interactivity payloads. Consent state changes and erasure complete synchronously (before the
 * ack), so a member's opt-out is applied even if the process dies right after. The Slack follow-ups (ephemeral
 * confirmation, App Home refresh) are best-effort remote calls that can hit 429 retry waits, so they run on a
 * drained executor off the ack path — Slack's 3-second interactivity budget must never depend on Slack's own API
 * latency.
 */
@Service
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackInteractivityHandler {

    private static final Logger log = LoggerFactory.getLogger(SlackInteractivityHandler.class);

    private final ExecutorService followUpExecutor;

    private final SlackWorkspaceResolver workspaceResolver;
    private final SlackConversationIdentityResolver identityResolver;
    private final SlackAppHomeService appHomeService;
    private final SlackParticipantConsentService participantConsentService;
    private final PrivacyJournal journal;
    private final PersonDataCopyFence copyFence;
    private final ApplicationEventPublisher events;
    private final SlackInteractiveReplyAdapter messageService;
    private final SlackConversationService conversationService;
    private final WeeklyDigestSubscriptions weeklyDigests;

    @Autowired
    public SlackInteractivityHandler(
            SlackWorkspaceResolver workspaceResolver,
            SlackConversationIdentityResolver identityResolver,
            SlackAppHomeService appHomeService,
            SlackParticipantConsentService participantConsentService,
            PrivacyJournal journal,
            PersonDataCopyFence copyFence,
            ApplicationEventPublisher events,
            SlackInteractiveReplyAdapter messageService,
            SlackConversationService conversationService,
            WeeklyDigestSubscriptions weeklyDigests) {
        this(
                workspaceResolver,
                identityResolver,
                appHomeService,
                participantConsentService,
                journal,
                copyFence,
                events,
                messageService,
                conversationService,
                weeklyDigests,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    SlackInteractivityHandler(
            SlackWorkspaceResolver workspaceResolver,
            SlackConversationIdentityResolver identityResolver,
            SlackAppHomeService appHomeService,
            SlackParticipantConsentService participantConsentService,
            PrivacyJournal journal,
            PersonDataCopyFence copyFence,
            ApplicationEventPublisher events,
            SlackInteractiveReplyAdapter messageService,
            SlackConversationService conversationService,
            WeeklyDigestSubscriptions weeklyDigests,
            ExecutorService followUpExecutor) {
        this.workspaceResolver = workspaceResolver;
        this.identityResolver = identityResolver;
        this.appHomeService = appHomeService;
        this.participantConsentService = participantConsentService;
        this.journal = journal;
        this.copyFence = copyFence;
        this.events = events;
        this.messageService = messageService;
        this.conversationService = conversationService;
        this.weeklyDigests = weeklyDigests;
        this.followUpExecutor = followUpExecutor;
    }

    public void handleBlockActions(JsonNode payload, Instant signedAt, boolean repeatedRequest) {
        String teamId = payload.path("team").path("id").asString("");
        String slackUserId = payload.path("user").path("id").asString("");
        String channelId = payload.path("channel").path("id").asString("");
        Optional<Long> workspaceOpt = workspaceResolver.resolveWorkspaceId(teamId);
        if (workspaceOpt.isEmpty()) {
            return;
        }
        long workspaceId = workspaceOpt.get();

        for (JsonNode action : payload.path("actions")) {
            String actionId = action.path("action_id").asString("");
            if (repeatedRequest
                    && !SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_OUT.equals(actionId)
                    && !SlackConsentBlocks.ACTION_PARTICIPANT_OPT_OUT.equals(actionId)) {
                continue;
            }
            // Slack dates every block action. The date orders a consent choice against a later one that Slack
            // delivered first. The signed timestamp keeps retries without an action timestamp at the same time.
            Instant decidedAt = Objects.requireNonNullElse(
                    SlackTs.toInstant(action.path("action_ts").asString("")), signedAt);
            switch (actionId) {
                case SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_OUT ->
                    handleChannelMessageOptOut(
                            workspaceId,
                            teamId,
                            slackUserId,
                            channelId,
                            decidedAt,
                            payload.path("view").path("hash").asString(null));
                case SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_IN ->
                    handleChannelMessageOptIn(workspaceId, teamId, slackUserId, decidedAt);
                case SlackConsentBlocks.ACTION_PARTICIPANT_OPT_OUT ->
                    handleChannelMessageOptOut(workspaceId, teamId, slackUserId, channelId, decidedAt, null);
                case SlackConversationPosts.FEEDBACK_ACTION ->
                    conversationService.handleFeedback(
                            teamId, channelId, slackUserId, action.path("value").asString(""));
                case SlackWeeklyDigestPointer.TURN_OFF_ACTION ->
                    turnOffWeeklyDigest(workspaceId, teamId, slackUserId, channelId);
                default -> log.debug("slack.interactivity: unhandled action_id {}", actionId);
            }
        }
    }

    private void handleChannelMessageOptOut(
            long workspaceId,
            String teamId,
            String slackUserId,
            String channelId,
            Instant decidedAt,
            @Nullable String homeViewHash) {
        Long memberId = identityResolver
                .resolveAccountId(workspaceId, teamId, slackUserId)
                .orElse(null);
        participantConsentService.requestChannelMessageOptOut(
                workspaceId,
                teamId,
                slackUserId,
                memberId,
                decidedAt,
                fact -> events.publishEvent(new OptOutConfirmation(
                        fact,
                        workspaceId,
                        memberId,
                        identityResolver.nativeSubject(teamId, slackUserId),
                        channelId,
                        decidedAt,
                        homeViewHash)));
    }

    /** Slack acknowledges transport after the local commit. This publication confirms consent after the bucket copy. */
    public record OptOutConfirmation(
            UUID fact,
            long workspaceId,
            @Nullable Long accountId,
            @ContentFree PersonIdentity subjectIdentity,
            @ContentFree String channelId,
            Instant decidedAt,
            @ContentFree @Nullable String homeViewHash) {}

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void onOptOutConfirmation(OptOutConfirmation event) {
        journal.confirmStored(event.fact());
        var subject = event.subjectIdentity();
        String teamId = subject.teamKey();
        String userId = subject.subject();
        BooleanSupplier eligible =
                () -> participantConsentService.isCurrentWithdrawal(event.workspaceId(), userId, event.decidedAt());
        var capture = copyFence.capture();
        try (capture) {
            confirmSlackOptOut(event, teamId, userId, eligible);
        }
    }

    private void confirmSlackOptOut(OptOutConfirmation event, String teamId, String userId, BooleanSupplier eligible) {
        if (!event.channelId().isBlank()) {
            try {
                messageService.sendEphemeralForTeam(
                        event.workspaceId(),
                        teamId,
                        event.channelId(),
                        userId,
                        new EphemeralMessage(
                                SlackConsentBlocks.optOutConfirmation(), SlackConsentBlocks.confirmationText()),
                        eligible);
            } catch (SlackSendException failure) {
                log.debug("Slack opt-out confirmation was unavailable: {}", failure.slackError());
            }
        }
        String homeViewHash = event.homeViewHash();
        if (homeViewHash != null && !homeViewHash.isBlank()) {
            try {
                appHomeService.publishHome(event.workspaceId(), teamId, userId, homeViewHash, eligible);
            } catch (SlackSendException failure) {
                log.debug("Slack opt-out App Home refresh was unavailable: {}", failure.slackError());
            }
        }
    }

    /**
     * Turns the weekly digest off for the account of the Slack user who pressed the button, before the ack, then
     * tells only them.
     */
    private void turnOffWeeklyDigest(long workspaceId, String teamId, String slackUserId, String channelId) {
        Optional<Long> accountId = identityResolver.resolveLinkedAccountId(teamId, slackUserId);
        if (accountId.isEmpty()) {
            return;
        }
        weeklyDigests.turnOff(accountId.get());
        if (channelId.isBlank()) {
            return;
        }
        followUpExecutor.execute(() -> {
            try {
                messageService.sendEphemeralForWorkspace(
                        workspaceId,
                        channelId,
                        slackUserId,
                        List.of(section(s -> s.text(markdownText(SlackWeeklyDigestPointer.TURNED_OFF)))),
                        SlackWeeklyDigestPointer.TURNED_OFF);
            } catch (SlackSendException e) {
                log.debug("slack.interactivity: weekly digest turn-off confirmation failed: {}", e.slackError());
            }
        });
    }

    private void handleChannelMessageOptIn(long workspaceId, String teamId, String slackUserId, Instant decidedAt) {
        participantConsentService.recordChannelMessageOptIn(workspaceId, teamId, slackUserId, decidedAt);
        followUpExecutor.execute(() -> refreshHomeBestEffort(teamId, slackUserId));
    }

    /** Drain in-flight Slack follow-ups (ephemeral confirmations, App Home refreshes) on shutdown. */
    @PreDestroy
    void shutdown() {
        followUpExecutor.shutdown();
        try {
            if (!followUpExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                followUpExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            followUpExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void refreshHomeBestEffort(String teamId, String slackUserId) {
        try {
            appHomeService.onHomeOpened(teamId, slackUserId);
        } catch (SlackSendException e) {
            log.debug(
                    "slack.interactivity: app home refresh failed for user {} in team {}: {}",
                    slackUserId,
                    teamId,
                    e.slackError());
        }
    }
}
