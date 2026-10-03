package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

class SlackConversationNudgeServiceTest extends BaseUnitTest {

    private static final long WS = 42L;
    private static final long RECIPIENT = 7L;
    private static final String TEAM = "T1";
    private static final String SLACK_USER = "U1";

    @Mock
    private ConnectionService connectionService;

    @Mock
    private AccountPreferencesQuery accountPreferencesQuery;

    @Mock
    private SlackMentorIdentityResolver identityResolver;

    @Mock
    private SlackMessageService slackMessageService;

    @Mock
    private MentorReadinessQuery mentorReadinessQuery;

    @Mock
    private MentorTurnRunner mentorTurnRunner;

    private SlackConversationNudgeService service;

    @BeforeEach
    void setUp() {
        service = new SlackConversationNudgeService(
                connectionService,
                accountPreferencesQuery,
                identityResolver,
                slackMessageService,
                mentorReadinessQuery,
                mentorTurnRunner);
    }

    private static ConversationFeedbackPreparedEvent event(int unitCount) {
        return new ConversationFeedbackPreparedEvent(WS, RECIPIENT, unitCount);
    }

    private static ConversationFeedbackPreparedEvent event(long workspaceId, int unitCount) {
        return new ConversationFeedbackPreparedEvent(workspaceId, RECIPIENT, unitCount);
    }

    private Connection stubActiveConnection() {
        when(mentorReadinessQuery.isReady(WS)).thenReturn(true);
        Connection connection = mock(Connection.class);
        when(connectionService.findActive(WS, IntegrationKind.SLACK)).thenReturn(Optional.of(connection));
        return connection;
    }

    private void stubRecipient(boolean practiceFeedbackDeliveryEnabled) {
        when(accountPreferencesQuery.practiceFeedbackDeliveryEnabled(RECIPIENT))
                .thenReturn(practiceFeedbackDeliveryEnabled);
    }

    private void stubAllGuardsPass() {
        Connection connection = stubActiveConnection();
        when(connection.getInstanceKey()).thenReturn(TEAM);
        stubRecipient(true);
        when(identityResolver.resolveSlackUserId(RECIPIENT, TEAM)).thenReturn(Optional.of(SLACK_USER));
    }

    @Test
    void allGuardsPass_sendsOneCountOnlyDmToTheRecipient() {
        stubAllGuardsPass();

        service.onConversationFeedbackPrepared(event(2));

        ArgumentCaptor<String> fallback = ArgumentCaptor.forClass(String.class);
        verify(slackMessageService).sendForWorkspace(eq(WS), eq(SLACK_USER), anyList(), fallback.capture());
        assertThat(fallback.getValue())
                .isEqualTo("You have 2 pieces of feedback to explore. Reply here to go through them.");
    }

    @Test
    void singleUnit_usesSingularCopy() {
        stubAllGuardsPass();

        service.onConversationFeedbackPrepared(event(1));

        verify(slackMessageService)
                .sendForWorkspace(
                        eq(WS),
                        eq(SLACK_USER),
                        anyList(),
                        eq("You have 1 piece of feedback to explore. Reply here to go through it."));
    }

    @Test
    void secondEventInsideCooldownWindow_sendsExactlyOneDm() {
        stubAllGuardsPass();

        service.onConversationFeedbackPrepared(event(2));
        service.onConversationFeedbackPrepared(event(1));

        verify(slackMessageService, times(1)).sendForWorkspace(eq(WS), eq(SLACK_USER), anyList(), anyString());
    }

    @Test
    void sameRecipientInDifferentWorkspace_hasIndependentCooldown() {
        long otherWorkspace = 43L;
        String otherTeam = "T2";
        String otherSlackUser = "U2";
        stubAllGuardsPass();
        Connection connection = mock(Connection.class);
        when(connection.getInstanceKey()).thenReturn(otherTeam);
        when(mentorReadinessQuery.isReady(otherWorkspace)).thenReturn(true);
        when(connectionService.findActive(otherWorkspace, IntegrationKind.SLACK))
                .thenReturn(Optional.of(connection));
        when(identityResolver.resolveSlackUserId(RECIPIENT, otherTeam)).thenReturn(Optional.of(otherSlackUser));

        service.onConversationFeedbackPrepared(event(WS, 2));
        service.onConversationFeedbackPrepared(event(otherWorkspace, 2));

        verify(slackMessageService).sendForWorkspace(eq(WS), eq(SLACK_USER), anyList(), anyString());
        verify(slackMessageService).sendForWorkspace(eq(otherWorkspace), eq(otherSlackUser), anyList(), anyString());
    }

    @ParameterizedTest
    @EnumSource(names = {"NO_AI", "UNAVAILABLE"})
    void shouldInviteOnLaterEventWhenRecipientBecomesEligible(MentorRefusal refusal) {
        stubAllGuardsPass();
        when(mentorTurnRunner.refusal(WS, RECIPIENT))
                .thenReturn(Optional.of(refusal))
                .thenReturn(Optional.empty());

        service.onConversationFeedbackPrepared(event(2));
        verifyNoInteractions(slackMessageService);

        service.onConversationFeedbackPrepared(event(1));
        verify(slackMessageService, times(1)).sendForWorkspace(eq(WS), eq(SLACK_USER), anyList(), anyString());
    }

    @Test
    void noActiveSlackConnection_skipsSilently() {
        when(mentorReadinessQuery.isReady(WS)).thenReturn(true);
        when(connectionService.findActive(WS, IntegrationKind.SLACK)).thenReturn(Optional.empty());

        service.onConversationFeedbackPrepared(event(2));

        verifyNoInteractions(identityResolver, slackMessageService);
    }

    @Test
    void disabledMentor_skipsBeforeResolvingAnyDeliveryRoute() {
        when(mentorReadinessQuery.isReady(WS)).thenReturn(false);

        service.onConversationFeedbackPrepared(event(2));

        verifyNoInteractions(connectionService, accountPreferencesQuery, identityResolver, slackMessageService);
    }

    @Test
    void recipientOptedOutOfFeedbackDelivery_skipsSilently() {
        stubActiveConnection();
        stubRecipient(false);

        service.onConversationFeedbackPrepared(event(2));

        verifyNoInteractions(identityResolver, slackMessageService);
    }

    @Test
    void recipientWithoutSlackIdentityLink_skipsSilently() {
        Connection connection = stubActiveConnection();
        when(connection.getInstanceKey()).thenReturn(TEAM);
        stubRecipient(true);
        when(identityResolver.resolveSlackUserId(RECIPIENT, TEAM)).thenReturn(Optional.empty());

        service.onConversationFeedbackPrepared(event(2));

        verifyNoInteractions(slackMessageService);
    }

    @Test
    void preferenceLookupFailure_failsClosed() {
        stubActiveConnection();
        when(accountPreferencesQuery.practiceFeedbackDeliveryEnabled(RECIPIENT))
                .thenThrow(new IllegalStateException("database unavailable"));

        service.onConversationFeedbackPrepared(event(2));

        verifyNoInteractions(identityResolver, slackMessageService);
    }

    @Test
    void sendFailure_releasesTheCooldownWindow_soTheNextEventRetries() {
        stubAllGuardsPass();
        doThrow(new SlackSendException(WS, SLACK_USER, "transport_failure"))
                .doNothing()
                .when(slackMessageService)
                .sendForWorkspace(eq(WS), eq(SLACK_USER), anyList(), anyString());

        service.onConversationFeedbackPrepared(event(2));
        service.onConversationFeedbackPrepared(event(2));

        verify(slackMessageService, times(2)).sendForWorkspace(eq(WS), eq(SLACK_USER), anyList(), anyString());
    }

    @Test
    void zeroUnitCount_isIgnored() {
        service.onConversationFeedbackPrepared(event(0));

        verifyNoInteractions(connectionService, accountPreferencesQuery, identityResolver, slackMessageService);
    }
}
