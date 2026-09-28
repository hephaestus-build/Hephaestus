package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackAssistantEventHandler;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelJoinNoticeHandler;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackMentorService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackUninstallService;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackAppHomeService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.Message;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class SlackEventMessageHandlerTest extends BaseUnitTest {

    private final NatsMessageDeserializer deserializer =
            new NatsMessageDeserializer(JsonMapper.builder().build());

    @Mock
    private SlackMentorService mentorService;

    @Mock
    private SlackAppHomeService appHomeService;

    @Mock
    private SlackAssistantEventHandler assistantEventHandler;

    @Mock
    private SlackChannelJoinNoticeHandler joinNoticeHandler;

    @Mock
    private SlackUninstallService uninstallService;

    private SlackMentorDmMessageHandler dmHandler;
    private SlackAppHomeOpenedMessageHandler appHomeHandler;
    private SlackMemberJoinedChannelMessageHandler joinHandler;
    private SlackAppUninstalledMessageHandler appUninstalledHandler;
    private SlackTokensRevokedMessageHandler tokensRevokedHandler;

    @BeforeEach
    void setUp() {
        dmHandler = new SlackMentorDmMessageHandler(mentorService, deserializer);
        appHomeHandler = new SlackAppHomeOpenedMessageHandler(
                appHomeService, assistantEventHandler, mentorService, deserializer);
        joinHandler = new SlackMemberJoinedChannelMessageHandler(joinNoticeHandler, deserializer);
        appUninstalledHandler = new SlackAppUninstalledMessageHandler(uninstallService, deserializer);
        tokensRevokedHandler = new SlackTokensRevokedMessageHandler(uninstallService, deserializer);
    }

    @Test
    void dmMessageRoutesToMentorService() {
        dmHandler.onMessage(
                nats(
                        "slack.T1.U1.message_im",
                        "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"im\",\"channel\":\"D1\",\"user\":\"U1\",\"text\":\"help\",\"ts\":\"100.2\",\"thread_ts\":\"100.1\"}}"));

        verify(mentorService).handleDm("T1", "D1", "U1", "help", "100.2", "100.1");
    }

    @Test
    void dmMessageWithoutThreadTsFallsBackToMessageTs() {
        dmHandler.onMessage(
                nats(
                        "slack.T1.U1.message_im",
                        "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"im\",\"channel\":\"D1\",\"user\":\"U1\",\"text\":\"help\",\"ts\":\"100.2\"}}"));

        verify(mentorService).handleDm("T1", "D1", "U1", "help", "100.2", "100.2");
    }

    @Test
    void dmMessageFallsBackToAuthorizationTeamId() {
        dmHandler.onMessage(
                nats(
                        "slack.T1.U1.message_im",
                        "{\"authorizations\":[{\"team_id\":\"T1\"}],\"event\":{\"type\":\"message\",\"channel_type\":\"im\",\"channel\":\"D1\",\"user\":\"U1\",\"text\":\"help\",\"ts\":\"100.2\"}}"));

        verify(mentorService).handleDm("T1", "D1", "U1", "help", "100.2", "100.2");
    }

    @Test
    void botDmIsIgnored() {
        dmHandler.onMessage(
                nats(
                        "slack.T1.U1.message_im",
                        "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"im\",\"channel\":\"D1\",\"user\":\"U1\",\"bot_id\":\"B1\",\"text\":\"help\",\"ts\":\"100.1\"}}"));

        verify(mentorService, never())
                .handleDm(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any());
    }

    @Test
    void channelMessagesNeverRouteToMentorDm() {
        dmHandler.onMessage(
                nats(
                        "slack.T1.C1.message_im",
                        "{\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"channel\",\"channel\":\"C1\",\"user\":\"U1\",\"text\":\"hello\",\"ts\":\"100.1\"}}"));

        verify(mentorService, never())
                .handleDm(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any());
    }

    @Test
    void appHomeOpenedRendersHomeOnlyForHomeTab() {
        appHomeHandler.onMessage(nats(
                "slack.T1.U1.app_home_opened",
                "{\"team_id\":\"T1\",\"event\":{\"type\":\"app_home_opened\",\"tab\":\"home\",\"user\":\"U1\"}}"));

        verify(appHomeService).onHomeOpened("T1", "U1");
        verify(mentorService).prepare("T1", "U1");
    }

    @Test
    void appHomeOpenedMessagesTabRoutesToAgentMessagesLifecycle() {
        appHomeHandler.onMessage(
                nats(
                        "slack.T1.U1.app_home_opened",
                        "{\"team_id\":\"T1\",\"event\":{\"type\":\"app_home_opened\",\"tab\":\"messages\",\"user\":\"U1\",\"channel\":\"D1\"}}"));

        verify(assistantEventHandler).onMessagesOpened(ArgumentMatchers.eq("T1"), ArgumentMatchers.any(JsonNode.class));
        verify(appHomeService, never()).onHomeOpened(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        verify(mentorService).prepare("T1", "U1");
    }

    @Test
    void memberJoinedRoutesToJustInTimeNotice() {
        joinHandler.onMessage(
                nats(
                        "slack.T1.C1.member_joined_channel",
                        "{\"team_id\":\"T1\",\"event\":{\"type\":\"member_joined_channel\",\"channel\":\"C1\",\"user\":\"U1\"}}"));

        verify(joinNoticeHandler)
                .onMemberJoined(
                        ArgumentMatchers.eq("T1"), ArgumentMatchers.any(JsonNode.class), ArgumentMatchers.eq(true));
    }

    @Test
    void staleMemberJoinedReplaySuppressesTheNoticeButStillRegisters() {
        joinHandler.onMessage(
                nats(
                        "slack.T1.C1.member_joined_channel",
                        "{\"team_id\":\"T1\",\"event_time\":1,\"event\":{\"type\":\"member_joined_channel\",\"channel\":\"C1\",\"user\":\"U1\"}}"));

        // The durable bot-self-join registration must survive a stale redelivery; only the
        // time-sensitive ephemeral notice is suppressed (noticeAllowed = false).
        verify(joinNoticeHandler)
                .onMemberJoined(
                        ArgumentMatchers.eq("T1"), ArgumentMatchers.any(JsonNode.class), ArgumentMatchers.eq(false));
    }

    @Test
    void uninstallEventsRouteToTeardown() {
        appUninstalledHandler.onMessage(nats(
                "slack.T1.workspace.app_uninstalled",
                "{\"team_id\":\"T1\",\"event_id\":\"Ev1\",\"event\":{\"type\":\"app_uninstalled\"}}"));
        tokensRevokedHandler.onMessage(
                nats(
                        "slack.T1.workspace.tokens_revoked",
                        "{\"team_id\":\"T1\",\"event_id\":\"Ev2\",\"event\":{\"type\":\"tokens_revoked\",\"tokens\":{\"bot\":[\"U9\"]}}}"));

        verify(uninstallService).onUninstall("T1", "app_uninstalled", "Ev1");
        verify(uninstallService).onUninstall("T1", "tokens_revoked", "Ev2");
    }

    @Test
    void userTokenOnlyRevocationDoesNotTearDownTheWorkspace() {
        // A member revoking their personal Sign-in-with-Slack authorization fires tokens_revoked with only
        // oauth entries; the app is still installed and the bot token is valid, so nothing may be purged.
        tokensRevokedHandler.onMessage(
                nats(
                        "slack.T1.workspace.tokens_revoked",
                        "{\"team_id\":\"T1\",\"event_id\":\"Ev3\",\"event\":{\"type\":\"tokens_revoked\",\"tokens\":{\"oauth\":[\"U1\"],\"bot\":[]}}}"));

        verify(uninstallService, never())
                .onUninstall(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

    private static Message nats(String ignoredSubject, String body) {
        Message message = Mockito.mock(Message.class);
        when(message.getData()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
