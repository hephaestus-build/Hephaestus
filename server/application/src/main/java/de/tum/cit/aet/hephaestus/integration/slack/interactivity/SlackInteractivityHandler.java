package de.tum.cit.aet.hephaestus.integration.slack.interactivity;

import de.tum.cit.aet.hephaestus.core.auth.spi.ConsentSource;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationCommand;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackConsentBlocks;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackParticipantConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackPersonErasureService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackWorkspaceResolver;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackAppHomeService;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Routes verified Slack interactivity payloads. Consent state changes and erasure complete synchronously (before the
 * ack), so a member's opt-out is applied even if the process dies right after. The Slack follow-ups (ephemeral
 * confirmation, App Home refresh) are best-effort remote calls that can hit 429 retry waits, so they run on a
 * drained executor off the ack path — Slack's 3-second interactivity budget must never depend on Slack's own API
 * latency.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackInteractivityHandler {

    private static final Logger log = LoggerFactory.getLogger(SlackInteractivityHandler.class);

    private final ExecutorService followUpExecutor;

    private final SlackWorkspaceResolver workspaceResolver;
    private final SlackMentorIdentityResolver identityResolver;
    private final ResearchParticipationCommand researchParticipationCommand;
    private final SlackAppHomeService appHomeService;
    private final SlackParticipantConsentService participantConsentService;
    private final SlackPersonErasureService personErasureService;
    private final SlackMessageService messageService;

    @Autowired
    public SlackInteractivityHandler(
            SlackWorkspaceResolver workspaceResolver,
            SlackMentorIdentityResolver identityResolver,
            ResearchParticipationCommand researchParticipationCommand,
            SlackAppHomeService appHomeService,
            SlackParticipantConsentService participantConsentService,
            SlackPersonErasureService personErasureService,
            SlackMessageService messageService) {
        this(
                workspaceResolver,
                identityResolver,
                researchParticipationCommand,
                appHomeService,
                participantConsentService,
                personErasureService,
                messageService,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    SlackInteractivityHandler(
            SlackWorkspaceResolver workspaceResolver,
            SlackMentorIdentityResolver identityResolver,
            ResearchParticipationCommand researchParticipationCommand,
            SlackAppHomeService appHomeService,
            SlackParticipantConsentService participantConsentService,
            SlackPersonErasureService personErasureService,
            SlackMessageService messageService,
            ExecutorService followUpExecutor) {
        this.workspaceResolver = workspaceResolver;
        this.identityResolver = identityResolver;
        this.researchParticipationCommand = researchParticipationCommand;
        this.appHomeService = appHomeService;
        this.participantConsentService = participantConsentService;
        this.personErasureService = personErasureService;
        this.messageService = messageService;
        this.followUpExecutor = followUpExecutor;
    }

    public void handleBlockActions(JsonNode payload) {
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
            switch (actionId) {
                case SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_OUT ->
                    handleChannelMessageOptOut(workspaceId, teamId, slackUserId, channelId, true);
                case SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_IN ->
                    handleChannelMessageOptIn(workspaceId, teamId, slackUserId);
                case SlackAppHomeService.ACTION_RESEARCH_OPT_OUT ->
                    handleResearchToggle(workspaceId, teamId, slackUserId, false);
                case SlackAppHomeService.ACTION_RESEARCH_OPT_IN ->
                    handleResearchToggle(workspaceId, teamId, slackUserId, true);
                case SlackConsentBlocks.ACTION_PARTICIPANT_OPT_OUT ->
                    handleChannelMessageOptOut(workspaceId, teamId, slackUserId, channelId, false);
                default -> log.debug("slack.interactivity: unhandled action_id {}", actionId);
            }
        }
    }

    private void handleResearchToggle(long workspaceId, String teamId, String slackUserId, boolean participate) {
        participantConsentService.recordResearchDecision(workspaceId, slackUserId, participate);
        setResearchParticipation(workspaceId, teamId, slackUserId, participate);
    }

    private void handleChannelMessageOptOut(
            long workspaceId, String teamId, String slackUserId, String channelId, boolean refreshHome) {
        participantConsentService.recordChannelMessageOptOut(workspaceId, slackUserId);
        Long memberId = identityResolver
                .resolveMemberId(workspaceId, teamId, slackUserId)
                .orElse(null);
        personErasureService.erasePerson(workspaceId, memberId, slackUserId);
        followUpExecutor.execute(() -> {
            if (channelId != null && !channelId.isBlank()) {
                try {
                    messageService.sendEphemeralForWorkspace(
                            workspaceId,
                            channelId,
                            slackUserId,
                            SlackConsentBlocks.optOutConfirmation(),
                            SlackConsentBlocks.confirmationText());
                } catch (SlackSendException e) {
                    log.debug(
                            "slack.interactivity: opt-out confirmation ephemeral failed for user {} in channel {}: {}",
                            slackUserId,
                            channelId,
                            e.slackError());
                }
            }
            if (refreshHome) {
                refreshHomeBestEffort(teamId, slackUserId);
            }
        });
    }

    private void handleChannelMessageOptIn(long workspaceId, String teamId, String slackUserId) {
        participantConsentService.recordChannelMessageOptIn(workspaceId, slackUserId);
        followUpExecutor.execute(() -> refreshHomeBestEffort(teamId, slackUserId));
    }

    private void setResearchParticipation(long workspaceId, String teamId, String slackUserId, boolean participate) {
        Optional<Long> memberId = identityResolver.resolveActiveMemberId(workspaceId, teamId, slackUserId);
        if (memberId.isEmpty()) {
            log.debug(
                    "slack.interactivity: research consent toggle from unlinked Slack user {} in team {} — skipping",
                    slackUserId,
                    teamId);
            return;
        }
        researchParticipationCommand.setForUserId(memberId.get(), participate, ConsentSource.SLACK_APP_HOME);
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
