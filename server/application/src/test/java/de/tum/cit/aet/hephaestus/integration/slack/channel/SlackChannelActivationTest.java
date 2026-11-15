package de.tum.cit.aet.hephaestus.integration.slack.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelConsentService.SlackChannelActivatedEvent;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEvent;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEventRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Settlement of a consent announcement. Each test fixes one guard of {@link SlackChannelActivation}: the action
 * settles the channel only while it is the latest activation request of an {@code ACTIVATING} row.
 */
class SlackChannelActivationTest extends BaseUnitTest {

    private static final long WS = 7L;
    private static final long REQUEST = 42L;
    private static final String CHANNEL = "C1";
    private static final UUID MARKER = UUID.fromString("f351dd84-23fe-4bec-a737-756f2e841f4f");
    private static final SlackChannelActivatedEvent ACTIVATION_KICK = new SlackChannelActivatedEvent(WS, CHANNEL);

    @Mock
    private SlackMonitoredChannelRepository channels;

    @Mock
    private SlackChannelConsentEventRepository events;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private SlackChannelActivation activation() {
        return new SlackChannelActivation(channels, events, eventPublisher);
    }

    private SlackMonitoredChannel channel(ConsentState state) {
        var c = new SlackMonitoredChannel();
        c.setId(11L);
        c.setWorkspaceId(WS);
        c.setSlackTeamId("T1");
        c.setSlackChannelId(CHANNEL);
        c.setConsentState(state);
        c.setConsentAnnouncementId(MARKER);
        lenient().when(channels.findByWorkspaceIdAndSlackChannelId(WS, CHANNEL)).thenReturn(Optional.of(c));
        lenient().when(channels.findForUpdate(WS, CHANNEL)).thenReturn(Optional.of(c));
        return c;
    }

    private void stubActivationRequest(Instant createdAt) {
        var request =
                new SlackChannelConsentEvent(WS, CHANNEL, ConsentState.FAILED, ConsentState.ACTIVATING, 5L, "retry");
        ReflectionTestUtils.setField(request, "id", REQUEST);
        ReflectionTestUtils.setField(request, "createdAt", createdAt);
        when(events.findActivationRequest(WS, REQUEST)).thenReturn(Optional.of(request));
    }

    private SlackMonitoredChannel activating() {
        var c = channel(ConsentState.ACTIVATING);
        stubActivationRequest(Instant.parse("2026-09-21T14:13:19Z"));
        when(events.findLatestActivationRevision(WS, CHANNEL)).thenReturn(REQUEST);
        return c;
    }

    private static SlackConsentAnnouncement announcement() {
        return new SlackConsentAnnouncement(CHANNEL, "T1", 11L, REQUEST, MARKER, "100.0", false);
    }

    @Test
    void theLatestAnnouncementOfAnActivatingChannelIsCurrent() {
        channel(ConsentState.ACTIVATING);
        when(events.findLatestActivationRevision(WS, CHANNEL)).thenReturn(REQUEST);

        assertThat(activation().isCurrent(WS, announcement())).isTrue();
    }

    @Test
    void anAnnouncementOfAnEarlierActivationIsNotCurrent() {
        channel(ConsentState.ACTIVATING);
        when(events.findLatestActivationRevision(WS, CHANNEL)).thenReturn(REQUEST + 1);

        assertThat(activation().isCurrent(WS, announcement())).isFalse();
    }

    @Test
    void anAnnouncementWithAnotherMarkerIsNotCurrent() {
        var c = channel(ConsentState.ACTIVATING);
        c.setConsentAnnouncementId(UUID.randomUUID());

        assertThat(activation().isCurrent(WS, announcement())).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = ConsentState.class,
            names = {"PENDING", "ACTIVE", "PAUSED", "FAILED", "REVOKED"})
    void anAnnouncementOfAChannelThatIsNotActivatingIsNotCurrent(ConsentState state) {
        channel(state);

        assertThat(activation().isCurrent(WS, announcement())).isFalse();
    }

    @Test
    void anAnnouncementOfAnotherChannelRowOrTeamIsNotCurrent() {
        channel(ConsentState.ACTIVATING);

        assertThat(activation()
                        .isCurrent(
                                WS, new SlackConsentAnnouncement(CHANNEL, "T1", 12L, REQUEST, MARKER, "100.0", false)))
                .isFalse();
        assertThat(activation()
                        .isCurrent(
                                WS, new SlackConsentAnnouncement(CHANNEL, "T2", 11L, REQUEST, MARKER, "100.0", false)))
                .isFalse();
    }

    @Test
    void confirmedAnnouncementActivatesAtTheSlackTimestampAndStartsHistorySync() {
        var c = activating();
        long micros = 1_790_000_000_123456L;

        activation().ended(WS, REQUEST, ActionState.DONE, micros);

        assertThat(c.getConsentState()).isEqualTo(ConsentState.ACTIVE);
        assertThat(c.getConsentAnnouncedAt()).isEqualTo(Instant.parse("2026-09-21T14:13:20.123456Z"));
        verify(eventPublisher).publishEvent(ACTIVATION_KICK);
        verify(channels).findForUpdate(WS, CHANNEL);
        ArgumentCaptor<SlackChannelConsentEvent> audit = ArgumentCaptor.captor();
        verify(events).save(audit.capture());
        assertThat(audit.getValue().getFromState()).isEqualTo(ConsentState.ACTIVATING);
        assertThat(audit.getValue().getToState()).isEqualTo(ConsentState.ACTIVE);
        assertThat(audit.getValue().getActorAccountId()).isNull();
        assertThat(audit.getValue().getReason()).isNull();
    }

    @Test
    void postAfterTheRequestStartsTheMonitoringPeriodAndHistorySyncAtThePost() {
        var c = activating();
        Instant post = Instant.parse("2026-09-21T14:13:20.123456Z");

        activation().ended(WS, REQUEST, ActionState.DONE, 1_790_000_000_123456L);

        assertThat(c.getIngestAfter()).isEqualTo(post);
        assertThat(c.getLastHistorySyncedTs()).isEqualTo("1790000000.123456");
    }

    @Test
    void firstAnnouncementUsesSlackTimeWhenTheRequestClockIsAhead() {
        var c = activating();
        var request =
                new SlackChannelConsentEvent(WS, CHANNEL, ConsentState.PENDING, ConsentState.ACTIVATING, 5L, null);
        ReflectionTestUtils.setField(request, "id", REQUEST);
        ReflectionTestUtils.setField(request, "createdAt", Instant.parse("2026-09-21T14:15:20Z"));
        when(events.findActivationRequest(WS, REQUEST)).thenReturn(Optional.of(request));

        activation().ended(WS, REQUEST, ActionState.DONE, 1_790_000_000_123456L);

        assertThat(c.getIngestAfter()).isEqualTo(Instant.parse("2026-09-21T14:13:20.123456Z"));
        assertThat(c.getLastHistorySyncedTs()).isEqualTo("1790000000.123456");
    }

    // An earlier post confirms disclosure, not permission to backfill the failed activation's gap.
    @Test
    void earlierPostOnRetryDoesNotBackfillTheFailedActivationGap() {
        var c = activating();
        stubActivationRequest(Instant.parse("2026-09-22T10:00:00Z"));

        activation().ended(WS, REQUEST, ActionState.DONE, 1_790_000_000_123456L);

        assertThat(c.getConsentAnnouncedAt()).isEqualTo(Instant.parse("2026-09-21T14:13:20.123456Z"));
        // The disclosure stamp keeps the earlier post. The live and sync floor is the retry request.
        assertThat(c.getIngestAfter()).isEqualTo(Instant.parse("2026-09-22T10:00:00Z"));
        assertThat(c.getLastHistorySyncedTs()).isEqualTo("1790071200.000000");
        assertThat(c.getConsentState()).isEqualTo(ConsentState.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(
            value = ActionState.class,
            names = {"FAILED", "UNCERTAIN", "CANCELLED"})
    void unconfirmedTerminalOutcomeRecordsFailureWithoutEnablingIngestion(ActionState outcome) {
        var c = activating();

        activation().ended(WS, REQUEST, outcome, null);

        assertThat(c.getConsentState()).isEqualTo(ConsentState.FAILED);
        assertThat(c.getConsentAnnouncedAt()).isNull();
        // A failed activation opens no monitoring period: live ingest has no floor (denies) and history has no start.
        assertThat(c.getIngestAfter()).isNull();
        assertThat(c.getLastHistorySyncedTs()).isNull();
        verify(eventPublisher, never()).publishEvent(ACTIVATION_KICK);
        ArgumentCaptor<SlackChannelConsentEvent> audit = ArgumentCaptor.captor();
        verify(events).save(audit.capture());
        assertThat(audit.getValue().getToState()).isEqualTo(ConsentState.FAILED);
        assertThat(audit.getValue().getReason()).isEqualTo("Consent announcement was not confirmed");
    }

    @Test
    void settlementForAnEarlierActivationCannotEnableTheCurrentRequest() {
        var c = activating();
        when(events.findLatestActivationRevision(WS, CHANNEL)).thenReturn(REQUEST + 1);

        activation().ended(WS, REQUEST, ActionState.DONE, 1L);

        assertThat(c.getConsentState()).isEqualTo(ConsentState.ACTIVATING);
        verify(channels, never()).save(any());
        verify(events, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void revokedChannelCannotBeActivatedByALateConfirmation() {
        var c = channel(ConsentState.REVOKED);
        stubActivationRequest(Instant.parse("2026-09-21T14:13:19Z"));

        activation().ended(WS, REQUEST, ActionState.DONE, 1L);

        assertThat(c.getConsentState()).isEqualTo(ConsentState.REVOKED);
        verify(channels, never()).save(any());
        verify(events, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void settlementWithoutAnActivationRequestChangesNothing() {
        when(events.findActivationRequest(WS, REQUEST)).thenReturn(Optional.empty());

        activation().ended(WS, REQUEST, ActionState.DONE, 1L);

        verify(channels, never()).save(any());
        verify(events, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }
}
