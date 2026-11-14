package de.tum.cit.aet.hephaestus.integration.slack.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slack.api.model.Message;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackLiveMessage;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackIngestService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService.HistoryPage;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/** What proves that Slack no longer holds a stored message, and what must not. */
class SlackHistoryReconcilerTest extends BaseUnitTest {

    private static final long WS = 42L;
    private static final String TEAM = "T1";
    private static final String CHANNEL = "C1";
    private static final String OTHER_CHANNEL = "C2";
    private static final Instant NOW = Instant.parse("2026-07-10T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Instant ANNOUNCED = NOW.minus(Duration.ofDays(20));
    private static final String WATERMARK = SlackTs.ofInstant(NOW.minus(Duration.ofDays(4)));
    private static final String OLDER_FROM = SlackTs.ofInstant(ANNOUNCED);
    private static final String DAY_ONE = SlackTs.ofInstant(NOW.minus(Duration.ofDays(10)));
    private static final String DAY_ONE_LATER =
            SlackTs.ofInstant(NOW.minus(Duration.ofDays(10)).plusSeconds(60));
    private static final String DAY_ONE_LATEST =
            SlackTs.ofInstant(NOW.minus(Duration.ofDays(10)).plusSeconds(120));
    private static final String DAY_TWO = SlackTs.ofInstant(NOW.minus(Duration.ofDays(9)));
    private static final String DAY_TWO_START = SlackTs.ofInstant(Instant.parse("2026-07-01T00:00:00Z"));
    private static final String DAY_ONE_START = SlackTs.ofInstant(Instant.parse("2026-06-30T00:00:00Z"));
    private static final String AFTER_WATERMARK = SlackTs.ofInstant(NOW.minus(Duration.ofDays(3)));
    private static final String AFTER_WATERMARK_2 = SlackTs.ofInstant(NOW.minus(Duration.ofDays(2)));

    @Mock
    private SlackMonitoredChannelRepository channels;

    @Mock
    private SlackThreadRepository threads;

    @Mock
    private SlackMessageRepository messages;

    @Mock
    private SlackMessageService slack;

    @Mock
    private SlackIngestService ingest;

    @Mock
    private ConnectionService connections;

    private SlackChannelHistorySyncService service;

    /** The channel's history as Slack holds it now; a read returns the messages of its range. */
    private final List<Message> history = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = serviceWith(properties(10, 5, 10));
        lenient().when(connections.findSlackConfig(WS)).thenReturn(Optional.empty());
        lenient().when(channels.findConsentState(eq(WS), anyString())).thenReturn(Optional.of(ConsentState.ACTIVE));
        lenient()
                .when(channels.advanceHistoryWatermark(eq(WS), anyString(), any(), any()))
                .thenReturn(1);
        lenient()
                .when(channels.findForHistorySync(WS, ConsentState.ACTIVE))
                .thenReturn(List.of(channel(CHANNEL, null, null)));
        lenient()
                .when(slack.fetchHistoryPage(eq(WS), anyString(), anyString(), anyString(), any(), anyInt()))
                .thenAnswer(read -> new HistoryPage(
                        history.stream()
                                .filter(m -> SlackTs.compare(m.getTs(), read.getArgument(2)) >= 0
                                        && SlackTs.compare(m.getTs(), read.getArgument(3)) <= 0)
                                .toList(),
                        null));
    }

    private static SlackSyncProperties properties(int history, int replies, int reconcile) {
        return new SlackSyncProperties("0 0 4 * * *", history, 15, Duration.ZERO, true, replies, true, reconcile);
    }

    private SlackChannelHistorySyncService serviceWith(SlackSyncProperties properties) {
        return new SlackChannelHistorySyncService(
                channels,
                threads,
                slack,
                ingest,
                connections,
                new SlackHistoryReconciler(messages, channels, slack, ingest, properties, CLOCK),
                properties,
                CLOCK);
    }

    private static SlackMonitoredChannel channel(
            String channelId, @Nullable String cursor, @Nullable Instant checkedAt) {
        SlackMonitoredChannel channel = new SlackMonitoredChannel();
        channel.setWorkspaceId(WS);
        channel.setSlackTeamId(TEAM);
        channel.setSlackChannelId(channelId);
        channel.setConsentState(ConsentState.ACTIVE);
        channel.setConsentAnnouncedAt(ANNOUNCED);
        channel.setIngestAfter(ANNOUNCED);
        channel.setLastHistorySyncedTs(WATERMARK);
        channel.setHistoryCheckCursorTs(cursor);
        channel.setHistoryCheckedAt(checkedAt);
        return channel;
    }

    private Message inSlack(String ts) {
        Message m = new Message();
        m.setTs(ts);
        m.setUser("U1");
        m.setText("text");
        history.add(m);
        return m;
    }

    private static SlackLiveMessage row(String ts) {
        return new SlackLiveMessage(ts, null);
    }

    private static SlackLiveMessage reply(String ts, String rootTs) {
        return new SlackLiveMessage(ts, rootTs);
    }

    private void storedInWindow(SlackLiveMessage... rows) {
        lenient()
                .when(messages.findLiveInThreadRange(eq(WS), eq(CHANNEL), eq(WATERMARK), anyString()))
                .thenReturn(List.of(rows));
    }

    private void storedOlder(String channelId, SlackLiveMessage... rows) {
        lenient()
                .when(messages.findLiveInThreadRange(WS, channelId, OLDER_FROM, WATERMARK))
                .thenReturn(List.of(rows));
    }

    private void storedOlder(SlackLiveMessage... rows) {
        storedOlder(CHANNEL, rows);
    }

    private void verifyNothingRemoved() {
        verify(ingest, never()).tombstoneMessage(any(), any(), any());
    }

    private void verifyOlderReads(int count) {
        verify(slack, times(count)).fetchHistoryPage(eq(WS), eq(CHANNEL), eq(DAY_ONE), anyString(), any(), anyInt());
    }

    @Test
    void shouldRemoveAMessageDeletedDuringAnOutageWhenEveryPageOfItsWindowWasRead() {
        storedInWindow(row(AFTER_WATERMARK), row(AFTER_WATERMARK_2));
        Message kept = inSlack(AFTER_WATERMARK);
        when(slack.fetchHistoryPage(eq(WS), eq(CHANNEL), eq(WATERMARK), anyString(), any(), anyInt()))
                .thenReturn(new HistoryPage(List.of(), "next"))
                .thenReturn(new HistoryPage(List.of(kept), null));

        service.syncWorkspace(WS);

        verify(ingest).tombstoneMessage(TEAM, CHANNEL, AFTER_WATERMARK_2);
        verify(ingest, never()).tombstoneMessage(TEAM, CHANNEL, AFTER_WATERMARK);
    }

    @Test
    void shouldRemoveNothingWhenTheWindowsLastPageFails() {
        storedInWindow(row(AFTER_WATERMARK));
        when(slack.fetchHistoryPage(eq(WS), eq(CHANNEL), eq(WATERMARK), anyString(), any(), anyInt()))
                .thenReturn(new HistoryPage(List.of(), "next"))
                .thenThrow(new SlackSendException(WS, CHANNEL, "ratelimited"));

        service.syncWorkspace(WS);

        verifyNothingRemoved();
    }

    @Test
    void shouldRemoveAnOlderDeletedMessageWithoutIngestingAnythingItRead() {
        storedOlder(row(DAY_ONE), row(DAY_TWO));
        inSlack(DAY_TWO);
        inSlack(DAY_ONE_LATER); // in Slack but not stored: a paused gap, or an author who opted out

        service.syncWorkspace(WS);

        verify(ingest).tombstoneMessage(TEAM, CHANNEL, DAY_ONE);
        verify(ingest, never()).tombstoneMessage(TEAM, CHANNEL, DAY_TWO);
        verify(ingest, never()).ingestChannelMessage(any(), any(), any(), any(), any(), any());
        verify(ingest, never()).editMessage(any(), any(), any());
        verify(channels).completeHistoryCheck(WS, CHANNEL, NOW);
    }

    @Test
    void shouldRemoveNothingWhenALaterPageIsRateLimited() {
        storedOlder(row(DAY_ONE), row(DAY_ONE_LATER));
        when(slack.fetchHistoryPage(eq(WS), eq(CHANNEL), eq(DAY_ONE), anyString(), any(), anyInt()))
                .thenReturn(new HistoryPage(List.of(), "next"))
                .thenThrow(new SlackSendException(WS, CHANNEL, "ratelimited"));

        var summary = service.syncWorkspace(WS);

        verifyNothingRemoved();
        assertThat(summary.failed()).isEqualTo(1);
        verify(channels).recordHistorySyncError(WS, CHANNEL, "Deletion check failed (ratelimited)");
        verify(channels, never()).recordHistoryCheckCursor(anyLong(), anyString(), anyString());
        verify(channels).deferHistoryCheck(WS, CHANNEL, NOW);
    }

    @Test
    void shouldContinueTheNextNightWhereTheBudgetStopped() {
        service = serviceWith(properties(10, 5, 1));
        storedOlder(row(DAY_ONE), row(DAY_TWO));

        var summary = service.syncWorkspace(WS);

        verify(channels).recordHistoryCheckCursor(WS, CHANNEL, DAY_TWO_START);
        verify(channels, never()).completeHistoryCheck(anyLong(), anyString(), any());
        verify(ingest).tombstoneMessage(TEAM, CHANNEL, DAY_TWO);
        verifyOlderReads(0);
        assertThat(summary.budgetExhausted())
                .as("a pass that covers part of the history each night is planned, not a warning")
                .isFalse();
    }

    @Test
    void shouldStartBelowTheCursorOfAnUnfinishedPass() {
        when(channels.findForHistorySync(WS, ConsentState.ACTIVE))
                .thenReturn(List.of(channel(CHANNEL, DAY_TWO_START, null)));
        storedOlder(row(DAY_ONE), row(DAY_TWO));

        service.syncWorkspace(WS);

        verify(ingest).tombstoneMessage(TEAM, CHANNEL, DAY_ONE);
        verify(ingest, never()).tombstoneMessage(TEAM, CHANNEL, DAY_TWO);
        verify(channels).recordHistoryCheckCursor(WS, CHANNEL, DAY_ONE_START);
        verify(channels).completeHistoryCheck(WS, CHANNEL, NOW);
    }

    @Test
    void shouldRemoveNothingFromADayTheBudgetCouldNotFinish() {
        service = serviceWith(properties(10, 5, 2));
        storedOlder(row(DAY_ONE), row(DAY_TWO));
        when(slack.fetchHistoryPage(eq(WS), eq(CHANNEL), eq(DAY_ONE), anyString(), any(), anyInt()))
                .thenReturn(new HistoryPage(List.of(), "next"));

        service.syncWorkspace(WS);

        verify(ingest, never()).tombstoneMessage(TEAM, CHANNEL, DAY_ONE);
        verify(channels, never()).recordHistoryCheckCursor(WS, CHANNEL, DAY_ONE_START);
    }

    @Test
    void shouldPassADayThatAWholeNightsBudgetCannotRead() {
        service = serviceWith(properties(10, 5, 1));
        storedOlder(row(DAY_ONE));
        when(slack.fetchHistoryPage(eq(WS), eq(CHANNEL), eq(DAY_ONE), anyString(), any(), anyInt()))
                .thenReturn(new HistoryPage(List.of(), "next"));

        service.syncWorkspace(WS);

        verifyNothingRemoved();
        verify(channels).recordHistoryCheckCursor(WS, CHANNEL, DAY_ONE_START);
    }

    @Test
    void shouldRemoveNothingWhenConsentChangesDuringTheRead() {
        storedOlder(row(DAY_ONE));
        when(channels.findConsentState(WS, CHANNEL))
                .thenReturn(Optional.of(ConsentState.ACTIVE)) // the window's request
                .thenReturn(Optional.of(ConsentState.ACTIVE)) // the first page of the older day
                .thenReturn(Optional.of(ConsentState.PAUSED));
        when(slack.fetchHistoryPage(eq(WS), eq(CHANNEL), eq(DAY_ONE), anyString(), any(), anyInt()))
                .thenReturn(new HistoryPage(List.of(), "next"));

        service.syncWorkspace(WS);

        verifyNothingRemoved();
    }

    @Test
    void shouldRemoveNothingWhenTheBotLostAccessToAPrivateChannelAndStillCheckTheNextChannel() {
        when(channels.findForHistorySync(WS, ConsentState.ACTIVE))
                .thenReturn(List.of(channel(CHANNEL, null, null), channel(OTHER_CHANNEL, null, null)));
        storedOlder(CHANNEL, row(DAY_ONE), row(DAY_TWO));
        storedOlder(OTHER_CHANNEL, row(DAY_ONE));
        when(slack.fetchHistoryPage(eq(WS), eq(CHANNEL), eq(DAY_TWO), anyString(), any(), anyInt()))
                .thenThrow(new SlackSendException(WS, CHANNEL, "not_in_channel"));

        var summary = service.syncWorkspace(WS);

        verify(ingest, never()).tombstoneMessage(TEAM, CHANNEL, DAY_ONE);
        verify(ingest, never()).tombstoneMessage(TEAM, CHANNEL, DAY_TWO);
        verifyOlderReads(0);
        verify(channels).recordHistorySyncError(WS, CHANNEL, "Deletion check failed (not_in_channel)");
        verify(ingest).tombstoneMessage(TEAM, OTHER_CHANNEL, DAY_ONE);
        assertThat(summary.failed()).isEqualTo(1);
    }

    @Test
    void shouldCheckTheChannelThatFinishedAPassLongestAgoFirst() {
        service = serviceWith(properties(10, 5, 1));
        when(channels.findForHistorySync(WS, ConsentState.ACTIVE))
                .thenReturn(List.of(
                        channel(CHANNEL, null, NOW.minus(Duration.ofDays(1))),
                        channel(OTHER_CHANNEL, null, NOW.minus(Duration.ofDays(3)))));
        storedOlder(CHANNEL, row(DAY_ONE));
        storedOlder(OTHER_CHANNEL, row(DAY_ONE));

        service.syncWorkspace(WS);

        verify(ingest).tombstoneMessage(TEAM, OTHER_CHANNEL, DAY_ONE);
        verify(ingest, never()).tombstoneMessage(TEAM, CHANNEL, DAY_ONE);
    }

    @Test
    void shouldNotReadAPausedOrArchivedChannel() {
        when(channels.findForHistorySync(WS, ConsentState.ACTIVE)).thenReturn(List.of());

        service.syncWorkspace(WS);

        verifyNoInteractions(slack, messages);
        verifyNothingRemoved();
    }

    @Test
    void shouldRemoveADeletedRootThatSlackKeepsAsAMarkerForItsReplies() {
        storedOlder(row(DAY_ONE), reply(DAY_ONE_LATER, DAY_ONE));
        Message marker = inSlack(DAY_ONE);
        marker.setSubtype("tombstone");
        marker.setReplyCount(1);

        service.syncWorkspace(WS);

        verify(ingest).tombstoneMessage(TEAM, CHANNEL, DAY_ONE);
        verify(ingest, never()).tombstoneMessage(TEAM, CHANNEL, DAY_ONE_LATER);
        verify(slack, never()).fetchRepliesPage(anyLong(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void shouldRemoveOnlyTheDeletedReplyAfterReadingItsWholeThread() {
        storedOlder(row(DAY_ONE), reply(DAY_ONE_LATER, DAY_ONE), reply(DAY_ONE_LATEST, DAY_ONE));
        inSlack(DAY_ONE).setReplyCount(1);
        Message root = new Message();
        root.setTs(DAY_ONE);
        Message kept = new Message();
        kept.setTs(DAY_ONE_LATER);
        when(slack.fetchRepliesPage(WS, CHANNEL, DAY_ONE, DAY_ONE, null, 15))
                .thenReturn(new HistoryPage(List.of(root), "next"));
        when(slack.fetchRepliesPage(WS, CHANNEL, DAY_ONE, DAY_ONE, "next", 15))
                .thenReturn(new HistoryPage(List.of(kept), null));

        service.syncWorkspace(WS);

        verify(ingest).tombstoneMessage(TEAM, CHANNEL, DAY_ONE_LATEST);
        verify(ingest, times(1)).tombstoneMessage(any(), any(), any());
    }

    @Test
    void shouldKeepTheCursorWhenTheBudgetEndsBeforeADaysRepliesWereCompared() {
        service = serviceWith(properties(10, 5, 2));
        storedOlder(row(DAY_TWO), row(DAY_ONE), reply(DAY_ONE_LATER, DAY_ONE));
        inSlack(DAY_TWO);
        inSlack(DAY_ONE);

        service.syncWorkspace(WS);

        verify(channels).recordHistoryCheckCursor(WS, CHANNEL, DAY_TWO_START);
        verify(channels, never()).recordHistoryCheckCursor(WS, CHANNEL, DAY_ONE_START);
        verify(ingest, never()).tombstoneMessage(TEAM, CHANNEL, DAY_ONE_LATER);
    }

    @Test
    void shouldRemoveTheRepliesOfARootOnlyWhenSlackConfirmsTheThreadIsGone() {
        storedOlder(row(DAY_ONE), reply(DAY_ONE_LATER, DAY_ONE));
        when(slack.fetchRepliesPage(WS, CHANNEL, DAY_ONE, DAY_ONE, null, 15))
                .thenThrow(new SlackSendException(WS, CHANNEL, "thread_not_found"));

        service.syncWorkspace(WS);

        verify(ingest).tombstoneMessage(TEAM, CHANNEL, DAY_ONE);
        verify(ingest).tombstoneMessage(TEAM, CHANNEL, DAY_ONE_LATER);
    }

    @Test
    void shouldApplyALostEditToAStoredMessageAndIgnoreSlacksDeletedMarker() {
        storedOlder(row(DAY_ONE), row(DAY_TWO));
        var edit = new Message.Edited();
        edit.setTs(DAY_TWO);
        Message edited = inSlack(DAY_ONE);
        edited.setText("edited");
        edited.setEdited(edit);
        Message marker = inSlack(DAY_TWO);
        marker.setSubtype("tombstone");
        marker.setEdited(edit);

        service.syncWorkspace(WS);

        verify(ingest).refreshEdit(WS, CHANNEL, DAY_ONE, "edited", DAY_TWO);
        verify(ingest, never()).refreshEdit(WS, CHANNEL, DAY_TWO, "text", DAY_TWO);
    }

    @Test
    void shouldNotRunTheOlderPassWhenItsBudgetIsZero() {
        service = serviceWith(properties(10, 5, 0));

        var summary = service.syncWorkspace(WS);

        verify(messages, never()).findLiveInThreadRange(WS, CHANNEL, OLDER_FROM, WATERMARK);
        assertThat(summary.budgetExhausted()).isFalse();
    }

    @Test
    void shouldKeepAConsentKickToItsChannelsWindow() {
        service.syncChannel(WS, CHANNEL);

        verify(messages, never()).findLiveInThreadRange(WS, CHANNEL, OLDER_FROM, WATERMARK);
    }
}
