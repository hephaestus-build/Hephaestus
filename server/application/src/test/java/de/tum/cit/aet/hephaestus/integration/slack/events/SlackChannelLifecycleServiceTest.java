package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelConsentService;
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
 * The lifecycle decision table: which Slack-side channel event maps onto which consent side effect, and that an
 * unresolved workspace or a blank channel id is always a silent no-op (the handlers run on the inbox consumer and
 * must never throw).
 */
@Tag("unit")
class SlackChannelLifecycleServiceTest extends BaseUnitTest {

    private static final long WORKSPACE = 42L;
    private static final String TEAM = "T1";
    private static final String CHANNEL = "C1";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Mock
    private SlackWorkspaceResolver workspaceResolver;

    @Mock
    private SlackChannelConsentService consentService;

    private SlackChannelLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new SlackChannelLifecycleService(workspaceResolver, consentService);
        lenient().when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.of(WORKSPACE));
    }

    @Test
    void botRemoved_pausesTheChannel() {
        service.onBotRemoved(TEAM, stringChannelEvent(CHANNEL));

        verify(consentService).pauseForPlatformEvent(WORKSPACE, CHANNEL, "bot removed from channel");
    }

    @Test
    void archived_pausesTheChannel() {
        service.onArchived(TEAM, stringChannelEvent(CHANNEL));

        verify(consentService).pauseForPlatformEvent(WORKSPACE, CHANNEL, "channel archived");
    }

    @Test
    void deleted_revokesAndErases() {
        service.onDeleted(TEAM, stringChannelEvent(CHANNEL));

        verify(consentService).revokeForPlatformEvent(WORKSPACE, CHANNEL, "channel deleted in Slack");
    }

    @Test
    void renamed_healsTheStoredName() {
        ObjectNode event = mapper.createObjectNode();
        ObjectNode channel = event.putObject("channel");
        channel.put("id", CHANNEL);
        channel.put("name", "new-name");

        service.onRenamed(TEAM, event);

        verify(consentService).renameChannel(WORKSPACE, CHANNEL, "new-name");
    }

    @Test
    void unresolvedTeam_isANoOpForEveryEvent() {
        when(workspaceResolver.resolveWorkspaceId("T-unknown")).thenReturn(Optional.empty());
        JsonNode event = stringChannelEvent(CHANNEL);

        service.onBotRemoved("T-unknown", event);
        service.onArchived("T-unknown", event);
        service.onDeleted("T-unknown", event);
        service.onRenamed("T-unknown", event);

        verifyNoInteractions(consentService);
    }

    @Test
    void blankChannel_isANoOp() {
        service.onBotRemoved(TEAM, stringChannelEvent(""));
        service.onDeleted(TEAM, mapper.createObjectNode());

        verifyNoInteractions(consentService);
    }

    private JsonNode stringChannelEvent(String channelId) {
        ObjectNode event = mapper.createObjectNode();
        event.put("channel", channelId);
        return event;
    }
}
