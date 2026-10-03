package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.slack.SlackHephaestusUiLinks;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackConsentBlocks;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Just-in-time join-notice unit tests. Deterministic (all collaborators mocked): the ephemeral consent notice is
 * posted ONLY for a real member joining an actively-ingested channel — never for a non-active channel, an unknown
 * workspace, or the bot's own join. Each test would fail if its gate were removed.
 */
@Tag("unit")
class SlackChannelJoinNoticeHandlerTest extends BaseUnitTest {

    private static final String TEAM = "T1";
    private static final long WORKSPACE_ID = 42L;
    private static final String CHANNEL = "C1";
    private static final String JOINER = "U1";
    private static final String BOT = "Ubot";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Mock
    private SlackWorkspaceResolver workspaceResolver;

    @Mock
    private SlackChannelConsentGate consentGate;

    @Mock
    private SlackParticipantConsentGate participantConsentGate;

    @Mock
    private SlackMessageService messageService;

    @Mock
    private SlackHephaestusUiLinks uiLinks;

    @Mock
    private SlackChannelConsentService consentService;

    private SlackChannelJoinNoticeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SlackChannelJoinNoticeHandler(
                workspaceResolver, consentGate, participantConsentGate, messageService, uiLinks, consentService);
        lenient().when(uiLinks.workspaceHomeUrl(WORKSPACE_ID)).thenReturn("https://heph.example/w/team");
    }

    private JsonNode joinEvent(String userId, String channelId) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "member_joined_channel");
        event.put("user", userId);
        event.put("channel", channelId);
        event.put("channel_type", "C");
        return event;
    }

    @Test
    void activeChannel_realJoiner_postsEphemeralConsentNotice() {
        when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.of(WORKSPACE_ID));
        when(consentGate.ingestAllowed(WORKSPACE_ID, CHANNEL)).thenReturn(true);
        when(participantConsentGate.ingestionAllowed(WORKSPACE_ID, JOINER)).thenReturn(true);
        when(messageService.resolveBotUserId(WORKSPACE_ID)).thenReturn(Optional.of(BOT));

        handler.onMemberJoined(TEAM, joinEvent(JOINER, CHANNEL), true);

        verify(messageService)
                .sendEphemeralForWorkspace(
                        eq(WORKSPACE_ID),
                        eq(CHANNEL),
                        eq(JOINER),
                        anyList(),
                        eq(SlackConsentBlocks.lateJoinFallbackText("https://heph.example/w/team")));
    }

    @Test
    void optedOutJoiner_noOp() {
        when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.of(WORKSPACE_ID));
        when(consentGate.ingestAllowed(WORKSPACE_ID, CHANNEL)).thenReturn(true);
        when(participantConsentGate.ingestionAllowed(WORKSPACE_ID, JOINER)).thenReturn(false);
        when(messageService.resolveBotUserId(WORKSPACE_ID)).thenReturn(Optional.of(BOT));

        handler.onMemberJoined(TEAM, joinEvent(JOINER, CHANNEL), true);

        verify(messageService).resolveBotUserId(WORKSPACE_ID);
        verify(messageService, never())
                .sendEphemeralForWorkspace(anyLong(), anyString(), anyString(), anyList(), anyString());
    }

    @Test
    void nonActiveChannel_noOp_afterCheckingWhetherJoinerIsTheBot() {
        when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.of(WORKSPACE_ID));
        when(messageService.resolveBotUserId(WORKSPACE_ID)).thenReturn(Optional.of(BOT));

        handler.onMemberJoined(TEAM, joinEvent(JOINER, CHANNEL), true);

        verify(messageService).resolveBotUserId(WORKSPACE_ID);
        verify(messageService, never())
                .sendEphemeralForWorkspace(anyLong(), anyString(), anyString(), anyList(), anyString());
    }

    @Test
    void botJoiningNonActiveChannel_registersPendingChannelWithoutNotice() {
        when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.of(WORKSPACE_ID));
        when(messageService.resolveBotUserId(WORKSPACE_ID)).thenReturn(Optional.of(BOT));

        handler.onMemberJoined(TEAM, joinEvent(BOT, CHANNEL), true);

        verify(consentService).register(WORKSPACE_ID, CHANNEL, null);
        verify(consentGate, never()).ingestAllowed(WORKSPACE_ID, CHANNEL);
        verify(messageService, never())
                .sendEphemeralForWorkspace(anyLong(), anyString(), anyString(), anyList(), anyString());
    }

    @Test
    void unknownWorkspace_noOp() {
        when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.empty());

        handler.onMemberJoined(TEAM, joinEvent(JOINER, CHANNEL), true);

        verifyNoInteractions(consentGate);
        verify(messageService, never()).resolveBotUserId(anyLong());
    }

    @Test
    void blankJoinerOrChannel_noOp() {
        handler.onMemberJoined(TEAM, joinEvent("", CHANNEL), true);
        handler.onMemberJoined(TEAM, joinEvent(JOINER, ""), true);

        verifyNoInteractions(workspaceResolver, consentGate, messageService);
    }
}
