package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.api.model.Message;
import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelActivation;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackConsentAnnouncement;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.json.JsonMapper;

class SlackConsentAdapterTest extends BaseUnitTest {
    private static final long WS = 7L;
    private static final String CHANNEL = "C1";
    private static final UUID MARKER = UUID.fromString("f351dd84-23fe-4bec-a737-756f2e841f4f");
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Mock
    private SlackMessageService client;

    @Mock
    private SlackChannelActivation activation;

    private ActionAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = SlackConsentTestSupport.adapter(client, activation, mapper);
        when(activation.isCurrent(eq(WS), any())).thenReturn(true);
        when(client.lookupConversationDetailed(WS, CHANNEL))
                .thenReturn(new SlackMessageService.ConversationLookup.Found(
                        new SlackMessageService.SlackConversationInfo(CHANNEL, "general", false, true, false)));
    }

    private ActionAttempt attempt(boolean started, boolean retry) {
        return new ActionAttempt(
                UUID.randomUUID(),
                WS,
                ActionKind.SLACK_CONSENT,
                SlackConsentAnnouncement.idempotencyKey(WS, 42L),
                mapper.valueToTree(new SlackConsentAnnouncement(CHANNEL, "T1", 11L, 42L, MARKER, "100.0", retry)),
                started ? 2 : 1,
                1L,
                started,
                null,
                () -> true,
                null);
    }

    @Test
    void confirmedPostCarriesItsTimestampToDomainSettlement() {
        when(client.isConnected(WS)).thenReturn(true);
        when(client.postMessage(eq(WS), eq(CHANNEL), any(), anyList(), any(), any()))
                .thenReturn(new SlackMessageService.Posted(CHANNEL, "101.123456"));
        assertThat(adapter.execute(attempt(false, false))).isEqualTo(new ActionResult.Done("101123456"));
        verify(client)
                .postMessage(eq(WS), eq(CHANNEL), any(), anyList(), any(), eq(SlackMentorPosts.noticeMetadata(MARKER)));
    }

    @Test
    void unknownTransportKeepsTheFenceAndReadsBackBeforeAnyFurtherWrite() {
        when(client.isConnected(WS)).thenReturn(true);
        when(client.postMessage(eq(WS), eq(CHANNEL), any(), anyList(), any(), any()))
                .thenThrow(new SlackSendException(WS, CHANNEL, "transport_failure"));
        assertThat(adapter.execute(attempt(false, false))).isInstanceOf(ActionResult.Reread.class);
    }

    @Test
    void reclaimedAttemptConfirmsTheStableMetadataWithoutPostingAgain() {
        when(client.isConnected(WS)).thenReturn(true);
        when(client.readWorkspaceMessages(eq(WS), eq(CHANNEL), any(), eq("100.0"), any(), eq(20)))
                .thenAnswer(inv -> {
                    Predicate<Message> matches = inv.getArgument(4);
                    var notice = new Message();
                    notice.setMetadata(SlackMentorPosts.noticeMetadata(MARKER));
                    assertThat(matches.test(notice)).isTrue();
                    return new SlackMessageService.Matches(List.of("101.123456"), false);
                });
        assertThat(adapter.execute(attempt(true, false))).isEqualTo(new ActionResult.Done("101123456"));
        verify(client, never()).postMessage(eq(WS), any(), any(), anyList(), any(), any());
    }

    @Test
    void rateLimitDefersThroughTheSharedPostTransport() {
        when(client.isConnected(WS)).thenReturn(true);
        when(client.postMessage(eq(WS), eq(CHANNEL), any(), anyList(), any(), any()))
                .thenThrow(new SlackSendException(WS, CHANNEL, "ratelimited", 120_000));
        assertThat(adapter.execute(attempt(false, false))).isEqualTo(new ActionResult.Defer(Duration.ofMinutes(2)));
    }

    @Test
    void explicitRetryFindsAnEarlierConfirmedPostWithoutDuplicatingTheAnnouncement() {
        when(client.isConnected(WS)).thenReturn(true);
        when(client.readWorkspaceMessages(eq(WS), eq(CHANNEL), any(), eq("100.0"), any(), eq(20)))
                .thenReturn(new SlackMessageService.Matches(List.of("101.123456"), false));
        assertThat(adapter.execute(attempt(false, true))).isEqualTo(new ActionResult.Done("101123456"));
        verify(client, never()).postMessage(eq(WS), any(), any(), anyList(), any(), any());
    }

    @Test
    void incompleteReadbackOnExplicitRetryCannotAuthorizeAnotherPost() {
        when(client.isConnected(WS)).thenReturn(true);
        when(client.readWorkspaceMessages(eq(WS), eq(CHANNEL), any(), eq("100.0"), any(), eq(20)))
                .thenReturn(new SlackMessageService.Matches(List.of(), true));
        assertThat(adapter.execute(attempt(false, true))).isInstanceOf(ActionResult.Reread.class);
        verify(client, never()).postMessage(eq(WS), any(), any(), anyList(), any(), any());
    }

    @Test
    void explicitRetryPostsOnlyAfterReadableHistoryProvesThePreviousAnnouncementAbsent() {
        when(client.isConnected(WS)).thenReturn(true);
        when(client.readWorkspaceMessages(eq(WS), eq(CHANNEL), any(), eq("100.0"), any(), eq(20)))
                .thenReturn(new SlackMessageService.Matches(List.of(), false));
        when(client.postMessage(eq(WS), eq(CHANNEL), any(), anyList(), any(), any()))
                .thenReturn(new SlackMessageService.Posted(CHANNEL, "102.0"));
        assertThat(adapter.execute(attempt(false, true))).isEqualTo(new ActionResult.Done("102000000"));
    }

    @Test
    void confirmedMissingChannelAfterWriteStaysUncertain() {
        when(client.lookupConversationDetailed(WS, CHANNEL))
                .thenReturn(new SlackMessageService.ConversationLookup.NotFound("channel_not_found"));
        assertThat(adapter.execute(attempt(true, false)))
                .isEqualTo(new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED));
    }
}
