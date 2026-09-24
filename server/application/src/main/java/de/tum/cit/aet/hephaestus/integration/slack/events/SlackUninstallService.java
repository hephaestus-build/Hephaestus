package de.tum.cit.aet.hephaestus.integration.slack.events;

import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorSlackThreadService;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationAttentionService;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspacePurgeAdapter;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles Slack app removal: mark the connection uninstalled and purge Slack raw + derived data.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
@WorkspaceAgnostic("Resolves the workspace from the Slack team_id in the uninstall payload, not from a scoped read")
public class SlackUninstallService {

    private static final Logger log = LoggerFactory.getLogger(SlackUninstallService.class);

    private final SlackWorkspaceResolver workspaceResolver;
    private final ConnectionService connectionService;
    private final SlackWorkspacePurgeAdapter purgeAdapter;
    private final MentorSlackThreadService mentorSlackThreadService;
    private final ConversationFeedbackErasure conversationFeedbackErasure;
    private final SlackMessageService messageService;
    private final IntegrationAttentionService attention;

    public SlackUninstallService(
            SlackWorkspaceResolver workspaceResolver,
            ConnectionService connectionService,
            SlackWorkspacePurgeAdapter purgeAdapter,
            MentorSlackThreadService mentorSlackThreadService,
            ConversationFeedbackErasure conversationFeedbackErasure,
            SlackMessageService messageService,
            IntegrationAttentionService attention) {
        this.workspaceResolver = workspaceResolver;
        this.connectionService = connectionService;
        this.purgeAdapter = purgeAdapter;
        this.mentorSlackThreadService = mentorSlackThreadService;
        this.conversationFeedbackErasure = conversationFeedbackErasure;
        this.messageService = messageService;
        this.attention = attention;
    }

    @Transactional
    public void onUninstall(String teamId, String eventType, String eventId) {
        var workspaceOpt = workspaceResolver.resolveWorkspaceId(teamId);
        if (workspaceOpt.isEmpty()) {
            log.info("Slack {} for team {} — no ACTIVE connection, nothing to tear down", eventType, teamId);
            return;
        }
        long workspaceId = workspaceOpt.get();
        connectionService.findActive(workspaceId, IntegrationKind.SLACK).ifPresent(connection -> {
            Connection changed = connectionService.transition(
                    connection,
                    new ConnectionService.TransitionRequest(
                            IntegrationState.UNINSTALLED,
                            "APP_UNINSTALLED".equals(eventType) || "app_uninstalled".equals(eventType)
                                    ? "APP_UNINSTALLED"
                                    : "TOKENS_REVOKED",
                            "SLACK",
                            teamId,
                            uninstallCorrelationId(teamId, eventType, eventId),
                            "Slack " + eventType + " received"));
            if ("tokens_revoked".equalsIgnoreCase(eventType)) {
                attention.report(
                        changed.getId(),
                        workspaceId,
                        IntegrationAttentionChangedEvent.Problem.CREDENTIAL_REVOKED,
                        false);
            }
        });
        int erasedConversationRows = conversationFeedbackErasure.eraseAllConversationForWorkspace(workspaceId);
        purgeAdapter.deleteWorkspaceData(workspaceId);
        int purgedThreads = mentorSlackThreadService.purgeSlackThreads(workspaceId);
        // A later reconnect may install a different Slack app; the cached bot user id must not survive teardown.
        messageService.evictBotUserId(workspaceId);
        log.info(
                "Slack {} for team {} → workspace {} torn down (connection UNINSTALLED, content purged, {} conversation-derived practice rows erased, {} mentor DM threads erased)",
                eventType,
                teamId,
                workspaceId,
                erasedConversationRows,
                purgedThreads);
    }

    private static String uninstallCorrelationId(String teamId, String eventType, String eventId) {
        String stableEventId = eventId == null || eventId.isBlank() ? teamId : eventId;
        return "slack-" + eventType + "-" + stableEventId;
    }
}
