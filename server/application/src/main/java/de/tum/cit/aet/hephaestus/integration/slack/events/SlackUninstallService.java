package de.tum.cit.aet.hephaestus.integration.slack.events;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionErasures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationAttentionService;
import de.tum.cit.aet.hephaestus.integration.core.consumer.DeliveryInbox;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackConnectionStrategy;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles Slack app removal: mark the connection uninstalled and purge Slack raw + derived data.
 */
@Service
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
@WorkspaceAgnostic("Resolves the workspace from the Slack team_id in the uninstall payload, not from a scoped read")
public class SlackUninstallService {

    private static final Logger log = LoggerFactory.getLogger(SlackUninstallService.class);

    private final SlackWorkspaceResolver workspaceResolver;
    private final ConnectionService connectionService;
    private final SlackConnectionStrategy connectionStrategy;
    private final IntegrationAttentionService attention;

    public SlackUninstallService(
            SlackWorkspaceResolver workspaceResolver,
            ConnectionService connectionService,
            SlackConnectionStrategy connectionStrategy,
            IntegrationAttentionService attention) {
        this.workspaceResolver = workspaceResolver;
        this.connectionService = connectionService;
        this.connectionStrategy = connectionStrategy;
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
        if (!DeliveryInbox.claim("slack.uninstall")) return;
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
            // The erase below removes what the connection mirrored, as a disconnect does.
            ConnectionErasures.record(Objects.requireNonNull(changed.getId()), workspaceId);
            if ("tokens_revoked".equalsIgnoreCase(eventType)) {
                attention.report(
                        changed.getId(),
                        workspaceId,
                        IntegrationAttentionChangedEvent.Problem.CREDENTIAL_REVOKED,
                        false);
            }
        });
        // An uninstall erases exactly what an admin disconnect erases.
        connectionStrategy.eraseLocalData(new IntegrationRef(IntegrationKind.SLACK, workspaceId, teamId));
    }

    private static String uninstallCorrelationId(String teamId, String eventType, String eventId) {
        String stableEventId = eventId == null || eventId.isBlank() ? teamId : eventId;
        return "slack-" + eventType + "-" + stableEventId;
    }
}
