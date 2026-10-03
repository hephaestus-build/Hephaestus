package de.tum.cit.aet.hephaestus.integration.slack.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService.ConversationLookup;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService.SlackConversationInfo;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * The refresher's core safety rule: only DEFINITIVE Slack answers (archived, not-a-member, channel_not_found) may
 * pause an ACTIVE channel — a transport failure must never transition consent state.
 */
@Tag("unit")
class SlackChannelMetadataRefresherTest extends BaseUnitTest {

    private static final long WS = 42L;
    private static final String CHANNEL = "C1";

    @Mock
    private SlackMonitoredChannelRepository monitoredChannelRepository;

    @Mock
    private SlackMessageService slackMessageService;

    @Mock
    private SlackChannelConsentService consentService;

    private SlackChannelMetadataRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new SlackChannelMetadataRefresher(monitoredChannelRepository, slackMessageService, consentService);
    }

    private SlackMonitoredChannel channel(ConsentState state, String name) {
        SlackMonitoredChannel c = new SlackMonitoredChannel();
        c.setWorkspaceId(WS);
        c.setSlackTeamId("T1");
        c.setSlackChannelId(CHANNEL);
        c.setChannelName(name);
        c.setConsentState(state);
        return c;
    }

    private void stub(SlackMonitoredChannel channel, ConversationLookup lookup) {
        when(monitoredChannelRepository.findByWorkspaceIdAndConsentStateNot(WS, ConsentState.REVOKED))
                .thenReturn(List.of(channel));
        when(slackMessageService.lookupConversationDetailed(WS, CHANNEL)).thenReturn(lookup);
    }

    private static ConversationLookup.Found found(String name, boolean member, boolean archived) {
        return new ConversationLookup.Found(new SlackConversationInfo(CHANNEL, name, false, member, archived));
    }

    @Test
    void healthyChannel_healsAStaleName_only() {
        stub(channel(ConsentState.ACTIVE, "old-name"), found("new-name", true, false));

        refresher.refreshWorkspace(WS);

        verify(consentService).renameChannel(WS, CHANNEL, "new-name");
        verify(consentService, never()).pauseForPlatformEvent(anyLong(), any(), any());
    }

    @Test
    void archivedChannel_pausesActive() {
        stub(channel(ConsentState.ACTIVE, "general"), found("general", true, true));

        refresher.refreshWorkspace(WS);

        verify(consentService).pauseForPlatformEvent(WS, CHANNEL, "The sync found that the channel is archived.");
    }

    @Test
    void botNoLongerMember_pausesActive() {
        stub(channel(ConsentState.ACTIVE, "general"), found("general", false, false));

        refresher.refreshWorkspace(WS);

        verify(consentService).pauseForPlatformEvent(WS, CHANNEL, "The sync found that the bot is not in the channel.");
    }

    @Test
    void channelNotFound_pausesActive() {
        stub(channel(ConsentState.ACTIVE, "general"), new ConversationLookup.NotFound("channel_not_found"));

        refresher.refreshWorkspace(WS);

        verify(consentService).pauseForPlatformEvent(WS, CHANNEL, "The sync found that the channel no longer exists.");
    }

    @Test
    void transportFailure_neverTransitionsConsent() {
        stub(channel(ConsentState.ACTIVE, "general"), new ConversationLookup.Unavailable("transport_failure"));

        refresher.refreshWorkspace(WS);

        verify(consentService, never()).pauseForPlatformEvent(anyLong(), any(), any());
        verify(consentService, never()).renameChannel(anyLong(), any(), any());
    }

    @Test
    void archivedButAlreadyPaused_isNotPausedAgain() {
        stub(channel(ConsentState.PAUSED, "general"), found("general", true, true));

        refresher.refreshWorkspace(WS);

        verify(consentService, never()).pauseForPlatformEvent(anyLong(), any(), any());
    }

    /**
     * Cancellation must be honoured per channel, not per pass: each channel is its own
     * {@code conversations.info} round trip, so a refresher that only checked once would keep calling Slack
     * for a workspace with hundreds of allow-listed channels long after the admin pressed Cancel.
     */
    @Test
    void cancellationRequestedUpFront_callsSlackForNoChannelAtAll() {
        when(monitoredChannelRepository.findByWorkspaceIdAndConsentStateNot(WS, ConsentState.REVOKED))
                .thenReturn(List.of(namedChannel("C1"), namedChannel("C2"), namedChannel("C3")));

        int checked = refresher.refreshWorkspace(WS, () -> true);

        assertThat(checked).isZero();
        verify(slackMessageService, never()).lookupConversationDetailed(anyLong(), any());
    }

    /** Mid-pass cancellation stops at the next channel boundary rather than draining the workspace. */
    @Test
    void cancellationRequestedAfterTheFirstChannel_stopsAtTheNextBoundary() {
        when(monitoredChannelRepository.findByWorkspaceIdAndConsentStateNot(WS, ConsentState.REVOKED))
                .thenReturn(List.of(namedChannel("C1"), namedChannel("C2"), namedChannel("C3")));
        when(slackMessageService.lookupConversationDetailed(eq(WS), anyString()))
                .thenReturn(new ConversationLookup.Unavailable("transport_failure"));
        AtomicInteger polls = new AtomicInteger();

        int checked = refresher.refreshWorkspace(WS, () -> polls.getAndIncrement() > 0);

        assertThat(checked).isEqualTo(1);
        verify(slackMessageService, times(1)).lookupConversationDetailed(anyLong(), any());
    }

    /** The uninterruptible overload the nightly cron uses still visits every channel. */
    @Test
    void withoutACancelSignal_everyChannelIsChecked() {
        when(monitoredChannelRepository.findByWorkspaceIdAndConsentStateNot(WS, ConsentState.REVOKED))
                .thenReturn(List.of(namedChannel("C1"), namedChannel("C2")));
        when(slackMessageService.lookupConversationDetailed(eq(WS), anyString()))
                .thenReturn(new ConversationLookup.Unavailable("transport_failure"));

        assertThat(refresher.refreshWorkspace(WS)).isEqualTo(2);
        verify(slackMessageService, times(2)).lookupConversationDetailed(anyLong(), any());
    }

    private SlackMonitoredChannel namedChannel(String channelId) {
        SlackMonitoredChannel c = channel(ConsentState.ACTIVE, "general");
        c.setSlackChannelId(channelId);
        return c;
    }
}
