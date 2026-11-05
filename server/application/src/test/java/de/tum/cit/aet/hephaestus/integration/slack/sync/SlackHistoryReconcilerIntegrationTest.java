package de.tum.cit.aet.hephaestus.integration.slack.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.slack.api.model.Message;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessage;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelConsentGate;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackIngestService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackParticipantConsentGate;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackWorkspaceResolver;
import de.tum.cit.aet.hephaestus.integration.slack.identity.SlackRosterIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService.HistoryPage;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The deletion check against real rows: what a lost deletion leaves behind, and what it must keep. */
class SlackHistoryReconcilerIntegrationTest extends BaseIntegrationTest {

    private static final String TEAM = "T1";
    private static final String OTHER_TEAM = "T2";
    private static final String CHANNEL = "C1";
    private static final Instant NOW = Instant.now();
    private static final Instant ANNOUNCED = NOW.minus(Duration.ofDays(170));
    private static final String DELETED = SlackTs.ofInstant(NOW.minus(Duration.ofDays(10)));
    private static final String KEPT =
            SlackTs.ofInstant(NOW.minus(Duration.ofDays(10)).plusSeconds(60));
    private static final String BEYOND_VISIBLE_HISTORY =
            SlackTs.ofInstant(NOW.minus(Duration.ofDays(90).minusHours(1)));
    private static final String WATERMARK = SlackTs.ofInstant(NOW.minus(Duration.ofDays(1)));
    private static final String RETENTION_FLOOR = SlackTs.ofInstant(NOW.minus(Duration.ofDays(180)));

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SlackMessageRepository messages;

    @Autowired
    private SlackThreadRepository threads;

    @Autowired
    private SlackMonitoredChannelRepository channels;

    @Autowired
    private SlackParticipantConsentRepository participantConsents;

    @Autowired
    private ConversationFeedbackErasure feedbackErasure;

    @Autowired
    private WorkspaceRepository workspaces;

    private final SlackMessageService slack = mock(SlackMessageService.class);

    /** The channel's history as Slack holds it now; a read returns the messages of its range. */
    private final List<Message> history = new ArrayList<>();

    private SlackHistoryReconciler reconciler;
    private SlackMonitoredChannel channel;
    private long workspaceId;
    private long otherWorkspaceId;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        workspaceId = workspaces
                .save(WorkspaceTestFixtures.activeWorkspace("slack-reconcile"))
                .getId();
        otherWorkspaceId = workspaces
                .save(WorkspaceTestFixtures.activeWorkspace("slack-reconcile-other"))
                .getId();
        channel = active(workspaceId, TEAM);
        active(otherWorkspaceId, OTHER_TEAM);
        SlackWorkspaceResolver resolver = mock(SlackWorkspaceResolver.class);
        when(resolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.of(workspaceId));
        var ingest = new SlackIngestService(
                resolver,
                channels,
                new SlackChannelConsentGate(channels),
                new SlackParticipantConsentGate(participantConsents),
                messages,
                threads,
                mock(SlackRosterIdentityResolver.class),
                feedbackErasure,
                true,
                new TransactionTemplate(transactionManager));
        var properties = new SlackSyncProperties("0 0 4 * * *", 10, 15, Duration.ZERO, true, 5, true, 10);
        reconciler = new SlackHistoryReconciler(
                messages, channels, slack, ingest, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        when(slack.fetchHistoryPage(eq(workspaceId), eq(CHANNEL), anyString(), anyString(), any(), anyInt()))
                .thenAnswer(read -> new HistoryPage(
                        history.stream()
                                .filter(m -> SlackTs.compare(m.getTs(), read.getArgument(2)) >= 0
                                        && SlackTs.compare(m.getTs(), read.getArgument(3)) <= 0)
                                .toList(),
                        null));

        store(workspaceId, TEAM, DELETED, "deleted during the outage");
        store(workspaceId, TEAM, KEPT, "still in Slack");
        store(otherWorkspaceId, OTHER_TEAM, DELETED, "another workspace");
        inSlack(KEPT);
    }

    private SlackMonitoredChannel active(long workspace, String team) {
        SlackMonitoredChannel monitored = new SlackMonitoredChannel();
        monitored.setWorkspaceId(workspace);
        monitored.setSlackTeamId(team);
        monitored.setSlackChannelId(CHANNEL);
        monitored.setConsentState(ConsentState.ACTIVE);
        monitored.setConsentAnnouncedAt(ANNOUNCED);
        monitored.setLastHistorySyncedTs(WATERMARK);
        return channels.save(monitored);
    }

    private Message inSlack(String ts) {
        Message message = new Message();
        message.setTs(ts);
        history.add(message);
        return message;
    }

    private void store(long workspace, String team, String ts, String text) {
        messages.insertIfAbsent(workspace, team, CHANNEL, ts, null, null, null, text);
    }

    private SlackMessage stored(long workspace, String ts) {
        return messages.findAll().stream()
                .filter(m -> m.getWorkspaceId() == workspace && m.getSlackTs().equals(ts))
                .findFirst()
                .orElseThrow();
    }

    private void reconcile() {
        reconciler.reconcileOlder(workspaceId, List.of(channel), RETENTION_FLOOR, () -> false);
    }

    @Test
    void shouldEraseTheTextOfADeletedMessageAndKeepEverythingElse() {
        reconcile();

        var deleted = stored(workspaceId, DELETED);
        assertThat(deleted.getDeletedAt()).isNotNull();
        assertThat(deleted.getText()).isNull();
        assertThat(stored(workspaceId, KEPT).getText()).isEqualTo("still in Slack");
        assertThat(stored(workspaceId, KEPT).getDeletedAt()).isNull();
        assertThat(stored(otherWorkspaceId, DELETED).getText()).isEqualTo("another workspace");
        var checked = channels.findById(channel.getId()).orElseThrow();
        assertThat(checked.getHistoryCheckedAt()).isNotNull();
        assertThat(checked.getHistoryCheckCursorTs()).isNull();
    }

    @Test
    void shouldKeepEveryMessageWhenTheReadFails() {
        when(slack.fetchHistoryPage(eq(workspaceId), eq(CHANNEL), anyString(), anyString(), any(), anyInt()))
                .thenThrow(new SlackSendException(workspaceId, CHANNEL, "ratelimited"));

        reconcile();

        assertThat(stored(workspaceId, DELETED).getText()).isEqualTo("deleted during the outage");
        assertThat(stored(workspaceId, KEPT).getText()).isEqualTo("still in Slack");
        var deferred = channels.findById(channel.getId()).orElseThrow();
        assertThat(deferred.getHistoryCheckedAt())
                .as("the failed channel goes behind the others")
                .isNotNull();
        assertThat(deferred.getHistoryCheckCursorTs())
                .as("the next turn retries the same day")
                .isNull();
    }

    @Test
    void shouldKeepAMessageOlderThanSlacksVisibleHistory() {
        store(workspaceId, TEAM, BEYOND_VISIBLE_HISTORY, "hidden by the free plan");

        reconcile();

        assertThat(stored(workspaceId, BEYOND_VISIBLE_HISTORY).getText()).isEqualTo("hidden by the free plan");
        assertThat(stored(workspaceId, DELETED).getText()).isNull();
    }

    @Test
    void shouldLeaveAMessageStoredInTheLastMinutesForALaterNight() {
        String justStored = SlackTs.ofInstant(NOW.minus(Duration.ofMinutes(5)));
        String settled = SlackTs.ofInstant(NOW.minus(Duration.ofMinutes(11)));
        store(workspaceId, TEAM, justStored, "posted a moment ago");
        store(workspaceId, TEAM, settled, "deleted a while ago");

        String oldest = SlackTs.ofInstant(NOW.minus(Duration.ofHours(1)));
        reconciler.removeAbsent(
                workspaceId,
                channel,
                reconciler.stored(workspaceId, CHANNEL, oldest, SlackTs.ofInstant(NOW)),
                Map.of(),
                new SlackSyncBudget(5, Duration.ZERO),
                () -> false);

        assertThat(stored(workspaceId, justStored).getText()).isEqualTo("posted a moment ago");
        assertThat(stored(workspaceId, settled).getText()).isNull();
    }

    @Test
    void shouldApplyALostEditToAStoredMessage() {
        Message edited = history.getFirst();
        edited.setText("edited during the outage");
        var edit = new Message.Edited();
        edit.setTs(WATERMARK);
        edited.setEdited(edit);
        inSlack(DELETED);

        reconcile();

        assertThat(stored(workspaceId, KEPT).getText()).isEqualTo("edited during the outage");
        assertThat(stored(workspaceId, DELETED).getDeletedAt()).isNull();
    }
}
