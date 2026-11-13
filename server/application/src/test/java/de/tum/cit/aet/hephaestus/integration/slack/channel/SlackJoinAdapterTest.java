package de.tum.cit.aet.hephaestus.integration.slack.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEventRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

class SlackJoinAdapterTest extends BaseUnitTest {
    private static final long WORKSPACE = 42L;
    private static final String CHANNEL = "C1";
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Mock
    private SlackMessageService client;

    @Mock
    private SlackMonitoredChannelRepository channels;

    @Mock
    private SlackChannelConsentEventRepository events;

    @Mock
    private ConnectionService connections;

    private SlackJoinAdapter adapter;

    @BeforeEach
    void setUp() {
        var channel = new SlackMonitoredChannel();
        channel.setId(11L);
        channel.setWorkspaceId(WORKSPACE);
        channel.setSlackChannelId(CHANNEL);
        channel.setSlackTeamId("T1");
        when(channels.findByWorkspaceIdAndSlackChannelId(WORKSPACE, CHANNEL)).thenReturn(Optional.of(channel));
        lenient()
                .when(connections.findSlackConfig(WORKSPACE))
                .thenReturn(Optional.of(new ConnectionConfig.SlackConfig("T1", "Slack", null, Set.of())));
        var beans = new StaticListableBeanFactory(Map.of("slack", client));
        adapter = new SlackJoinAdapter(
                beans.getBeanProvider(SlackMessageService.class), channels, events, connections, mapper);
    }

    private ActionAttempt attempt(boolean started, boolean fence) {
        return new ActionAttempt(
                UUID.randomUUID(),
                WORKSPACE,
                ActionKind.SLACK_JOIN,
                "slack_join:42:11",
                mapper.valueToTree(new SlackChannelJoin(CHANNEL, "T1", 11L, 0L)),
                started ? 2 : 1,
                1L,
                started,
                null,
                () -> fence,
                null);
    }

    private void membership(boolean joined) {
        when(client.lookupConversationDetailed(WORKSPACE, CHANNEL))
                .thenReturn(new SlackMessageService.ConversationLookup.Found(
                        new SlackMessageService.SlackConversationInfo(CHANNEL, "general", false, joined, false)));
    }

    @Test
    void shouldJoinOnlyAfterTheWriteFenceAccepts() {
        membership(false);
        assertThat(adapter.execute(attempt(false, true))).isEqualTo(new ActionResult.Done(CHANNEL));
        verify(client).joinPublicChannel(WORKSPACE, CHANNEL);
    }

    @Test
    void shouldRecoverAnUnknownJoinFromMembershipWithoutJoiningAgain() {
        membership(true);
        assertThat(adapter.execute(attempt(true, true))).isEqualTo(new ActionResult.Done(CHANNEL));
        verify(client, never()).joinPublicChannel(WORKSPACE, CHANNEL);
    }

    @Test
    void shouldRetryOnlyAfterReadbackProvesTheAppIsNotAMember() {
        membership(false);
        assertThat(adapter.execute(attempt(true, true))).isEqualTo(new ActionResult.Retry(Duration.ZERO));
        verify(client, never()).joinPublicChannel(WORKSPACE, CHANNEL);
    }

    @Test
    void shouldKeepUnreadableMembershipUncertain() {
        when(client.lookupConversationDetailed(WORKSPACE, CHANNEL))
                .thenReturn(new SlackMessageService.ConversationLookup.Unavailable("transport_failure"));
        assertThat(adapter.execute(attempt(true, true))).isInstanceOf(ActionResult.Reread.class);
        verify(client, never()).joinPublicChannel(WORKSPACE, CHANNEL);
    }

    @Test
    void shouldCancelAJoinFromAReplacedConsentSetup() {
        when(events.findLatestSetupRevision(WORKSPACE, CHANNEL)).thenReturn(1L);
        assertThat(adapter.execute(attempt(false, true)))
                .isEqualTo(new ActionResult.Cancelled(ActionStopReason.WITHDRAWN));
        verifyNoInteractions(client);
    }

    @Test
    void shouldNotClaimAbsenceAfterAnUnknownJoinOfAReplacedSetup() {
        when(events.findLatestSetupRevision(WORKSPACE, CHANNEL)).thenReturn(1L);
        assertThat(adapter.execute(attempt(true, true)))
                .isEqualTo(new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED));
        verifyNoInteractions(client);
    }

    @Test
    void shouldRefuseToJoinAfterItsFenceWasLost() {
        membership(false);
        assertThat(adapter.execute(attempt(false, false))).isInstanceOf(ActionResult.Abandoned.class);
        verify(client, never()).joinPublicChannel(WORKSPACE, CHANNEL);
    }

    @Test
    void shouldRecordSilentModeAsCancellation() {
        membership(false);
        doThrow(new SlackSendException(WORKSPACE, CHANNEL, "silent_mode_engaged"))
                .when(client)
                .joinPublicChannel(WORKSPACE, CHANNEL);
        assertThat(adapter.execute(attempt(false, true)))
                .isEqualTo(new ActionResult.Cancelled(ActionStopReason.SILENCED));
    }

    @Test
    void shouldDeferARateLimitedJoin() {
        membership(false);
        doThrow(new SlackSendException(WORKSPACE, CHANNEL, "ratelimited", 120_000))
                .when(client)
                .joinPublicChannel(WORKSPACE, CHANNEL);
        assertThat(adapter.execute(attempt(false, true))).isEqualTo(new ActionResult.Defer(Duration.ofMinutes(2)));
    }

    @Test
    void shouldReadMembershipAfterATransportFailure() {
        membership(false);
        doThrow(new SlackSendException(WORKSPACE, CHANNEL, "transport_failure"))
                .when(client)
                .joinPublicChannel(WORKSPACE, CHANNEL);
        assertThat(adapter.execute(attempt(false, true))).isInstanceOf(ActionResult.Reread.class);
    }
}
