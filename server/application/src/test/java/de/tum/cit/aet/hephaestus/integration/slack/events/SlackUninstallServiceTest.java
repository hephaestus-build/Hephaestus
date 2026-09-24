package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorSlackThreadService;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspacePurgeAdapter;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;

/**
 * Uninstall-routing unit tests: an {@code app_uninstalled}/{@code tokens_revoked} event flips the Slack
 * Connection to UNINSTALLED and then purges the workspace's Slack content; an unknown team is a safe no-op.
 */
@Tag("unit")
class SlackUninstallServiceTest extends BaseUnitTest {

    private static final long WORKSPACE = 42L;
    private static final String TEAM = "T1";

    @Mock
    private SlackWorkspaceResolver workspaceResolver;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private SlackWorkspacePurgeAdapter purgeAdapter;

    @Mock
    private MentorSlackThreadService mentorSlackThreadService;

    @Mock
    private ConversationFeedbackErasure conversationFeedbackErasure;

    @Mock
    private SlackMessageService messageService;

    @Mock
    private Connection connection;

    private SlackUninstallService service() {
        return new SlackUninstallService(
                workspaceResolver,
                connectionService,
                purgeAdapter,
                mentorSlackThreadService,
                conversationFeedbackErasure,
                messageService,
                org.mockito.Mockito.mock(
                        de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationAttentionService.class));
    }

    private static Stream<Arguments> eventTypeMapping() {
        return Stream.of(
                Arguments.of("app_uninstalled", "Ev1", "APP_UNINSTALLED", "slack-app_uninstalled-Ev1"),
                Arguments.of("tokens_revoked", "Ev2", "TOKENS_REVOKED", "slack-tokens_revoked-Ev2"));
    }

    @ParameterizedTest(name = "{0} maps to eventType {2}")
    @MethodSource("eventTypeMapping")
    void eventTypeMapsToConnectionTransition(
            String slackEventType, String eventId, String expectedEventType, String expectedCorrelationId) {
        when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.of(WORKSPACE));
        when(connectionService.findActive(WORKSPACE, IntegrationKind.SLACK)).thenReturn(Optional.of(connection));
        org.mockito.Mockito.lenient()
                .when(connectionService.transition(eq(connection), any()))
                .thenReturn(connection);

        service().onUninstall(TEAM, slackEventType, eventId);

        ArgumentCaptor<ConnectionService.TransitionRequest> captor =
                ArgumentCaptor.forClass(ConnectionService.TransitionRequest.class);
        verify(connectionService).transition(eq(connection), captor.capture());
        assertThat(captor.getValue().next()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(captor.getValue().eventType()).isEqualTo(expectedEventType);
        assertThat(captor.getValue().correlationId()).isEqualTo(expectedCorrelationId);
    }

    @Test
    void appUninstalled_purgesWorkspaceDataInOrder() {
        when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.of(WORKSPACE));
        when(connectionService.findActive(WORKSPACE, IntegrationKind.SLACK)).thenReturn(Optional.of(connection));
        org.mockito.Mockito.lenient()
                .when(connectionService.transition(eq(connection), any()))
                .thenReturn(connection);

        service().onUninstall(TEAM, "app_uninstalled", "Ev1");

        InOrder order = Mockito.inOrder(conversationFeedbackErasure, purgeAdapter);
        order.verify(conversationFeedbackErasure).eraseAllConversationForWorkspace(WORKSPACE);
        order.verify(purgeAdapter).deleteWorkspaceData(WORKSPACE);
        verify(mentorSlackThreadService).purgeSlackThreads(WORKSPACE);
    }

    @Test
    void unknownTeam_isNoOp() {
        when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.empty());

        service().onUninstall(TEAM, "app_uninstalled", "Ev1");

        verify(connectionService, never()).transition(ArgumentMatchers.any(), ArgumentMatchers.any());
        verifyNoInteractions(purgeAdapter, mentorSlackThreadService, conversationFeedbackErasure);
    }
}
