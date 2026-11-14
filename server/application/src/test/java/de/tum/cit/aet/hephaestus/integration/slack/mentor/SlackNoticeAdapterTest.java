package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.api.model.Message;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ModelLinkHosts;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackOnboardingService;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackMessages;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackReplies;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

class SlackNoticeAdapterTest extends BaseUnitTest {
    private static final long WORKSPACE = 42L;
    private static final String CHANNEL = "D9";
    private static final UUID ACTION = UUID.fromString("b17d596a-2f85-4cfa-91f5-74caf77b94a7");
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Mock
    private SlackMessageService client;

    @Mock
    private ConnectionService connections;

    private SlackNoticeAdapter adapter;

    @BeforeEach
    void setUp() {
        var beans = new StaticListableBeanFactory(Map.of("slack", client));
        var posts = new SlackPostAdapter(
                beans.getBeanProvider(SlackMessageService.class),
                beans.getBeanProvider(SlackWeeklyDigestPointer.class),
                mock(MentorSlackReplies.class),
                mock(MentorSlackMessages.class),
                mock(MentorTurnCompletion.class),
                mock(ModelLinkHosts.class),
                mapper);
        adapter =
                new SlackNoticeAdapter(posts, beans.getBeanProvider(SlackOnboardingService.class), connections, mapper);
        when(connections.findSlackConfig(WORKSPACE))
                .thenReturn(Optional.of(new ConnectionConfig.SlackConfig("T1", "Slack", null, Set.of())));
    }

    private static Message message(Message.Metadata metadata) {
        var message = new Message();
        message.setMetadata(metadata);
        return message;
    }

    private ActionAttempt attempt(boolean started, boolean fence) {
        return new ActionAttempt(
                ACTION,
                WORKSPACE,
                ActionKind.SLACK_NOTICE,
                "slack_notice:42:T1:D9:100.1",
                mapper.valueToTree(new SlackNotice(CHANNEL, null, "100.1", "Not available", false)),
                started ? 2 : 1,
                started ? 2L : 1L,
                started,
                null,
                () -> fence,
                new PersonIdentity(7L, "U1", "T1"));
    }

    @Test
    void shouldPostOneNoticeWithItsOwnReadbackMarker() {
        when(client.isConnected(WORKSPACE)).thenReturn(true);
        when(client.postMessage(eq(WORKSPACE), eq(CHANNEL), any(), anyList(), any(), any()))
                .thenReturn(new SlackMessageService.Posted(CHANNEL, "101.0"));

        var result = adapter.execute(attempt(false, true));

        assertThat(result).isInstanceOf(ActionResult.Done.class);
        verify(client)
                .postMessage(
                        WORKSPACE, CHANNEL, null, List.of(), "Not available", SlackMentorPosts.noticeMetadata(ACTION));
        var notice = message(SlackMentorPosts.noticeMetadata(ACTION));
        assertThat(SlackMentorPosts.isNoticeOf(notice, ACTION)).isTrue();
        assertThat(SlackMentorPosts.isPostOf(notice, ACTION)).isFalse();
    }

    @Test
    void shouldRecoverAnUnknownWriteFromItsNoticeMetadataWithoutPostingAgain() {
        when(client.isConnected(WORKSPACE)).thenReturn(true);
        when(client.postMessage(eq(WORKSPACE), eq(CHANNEL), any(), anyList(), any(), any()))
                .thenThrow(new SlackSendException(WORKSPACE, CHANNEL, "transport_failure"));
        when(client.readWorkspaceMessages(eq(WORKSPACE), eq(CHANNEL), any(), eq("100.1"), any()))
                .thenAnswer(invocation -> {
                    Predicate<Message> match = invocation.getArgument(4);
                    var answer = message(SlackMentorPosts.metadata(ACTION));
                    var notice = message(SlackMentorPosts.noticeMetadata(ACTION));
                    assertThat(match.test(answer)).isFalse();
                    assertThat(match.test(notice)).isTrue();
                    return new SlackMessageService.Matches(List.of("101.0"), false);
                });

        assertThat(adapter.execute(attempt(false, true))).isInstanceOf(ActionResult.Reread.class);
        assertThat(adapter.execute(attempt(true, true))).isInstanceOf(ActionResult.Done.class);
        verify(client, times(1)).postMessage(eq(WORKSPACE), eq(CHANNEL), any(), anyList(), any(), any());
    }

    @Test
    void shouldKeepAnIncompleteReadbackUncertain() {
        when(client.isConnected(WORKSPACE)).thenReturn(true);
        when(client.readWorkspaceMessages(eq(WORKSPACE), eq(CHANNEL), any(), eq("100.1"), any()))
                .thenReturn(new SlackMessageService.Matches(List.of(), true));

        assertThat(adapter.execute(attempt(true, true))).isInstanceOf(ActionResult.Reread.class);
        verify(client, never()).postMessage(any(Long.class), any(), any(), anyList(), any(), any());
    }

    @Test
    void shouldNotPostAgainWhenCompleteReadbackFindsNoNotice() {
        when(client.isConnected(WORKSPACE)).thenReturn(true);
        when(client.readWorkspaceMessages(eq(WORKSPACE), eq(CHANNEL), any(), eq("100.1"), any()))
                .thenReturn(new SlackMessageService.Matches(List.of(), false));

        assertThat(adapter.execute(attempt(true, true))).isEqualTo(new ActionResult.Failed(ActionStopReason.REJECTED));
        verify(client, never()).postMessage(any(Long.class), any(), any(), anyList(), any(), any());
    }

    @Test
    void shouldRespectTheProviderRetryAfterWithoutSpendingAnAttempt() {
        when(client.isConnected(WORKSPACE)).thenReturn(true);
        when(client.postMessage(eq(WORKSPACE), eq(CHANNEL), any(), anyList(), any(), any()))
                .thenThrow(new SlackSendException(WORKSPACE, CHANNEL, "ratelimited", 120_000));

        assertThat(adapter.execute(attempt(false, true))).isEqualTo(new ActionResult.Defer(Duration.ofMinutes(2)));
    }

    @Test
    void shouldHonorTheWriteFence() {
        when(client.isConnected(WORKSPACE)).thenReturn(true);
        assertThat(adapter.execute(attempt(false, false))).isInstanceOf(ActionResult.Abandoned.class);
        verify(client, never()).postMessage(any(Long.class), any(), any(), anyList(), any(), any());
    }

    @Test
    void shouldCancelBeforeWritingWhenSilentModeIsEngaged() {
        when(client.isConnected(WORKSPACE)).thenReturn(true);
        when(client.postMessage(eq(WORKSPACE), eq(CHANNEL), any(), anyList(), any(), any()))
                .thenThrow(new SlackSendException(WORKSPACE, CHANNEL, "silent_mode_engaged"));

        assertThat(adapter.execute(attempt(false, true)))
                .isEqualTo(new ActionResult.Cancelled(ActionStopReason.SILENCED));
    }

    @Test
    void shouldNotClaimAbsenceWhenTheConnectionEndsAfterAnUnknownWrite() {
        when(client.isConnected(WORKSPACE)).thenReturn(false);

        assertThat(adapter.execute(attempt(true, true)))
                .isEqualTo(new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED));
    }

    @Test
    void shouldRefuseANoticeFromThePreviousSlackTeamAfterReconnect() {
        when(connections.findSlackConfig(WORKSPACE))
                .thenReturn(Optional.of(new ConnectionConfig.SlackConfig("T_REPLACEMENT", "Slack", null, Set.of())));
        assertThat(adapter.execute(attempt(false, true)))
                .isEqualTo(new ActionResult.Cancelled(ActionStopReason.RECIPIENT_UNAVAILABLE));
        verify(client, never()).postMessage(any(Long.class), any(), any(), anyList(), any(), any());
    }
}
