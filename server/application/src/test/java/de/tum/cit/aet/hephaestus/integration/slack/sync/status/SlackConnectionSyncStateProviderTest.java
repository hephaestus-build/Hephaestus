package de.tum.cit.aet.hephaestus.integration.slack.sync.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncDetails;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResourceState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.slack.sync.SlackSyncProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

@Tag("unit")
class SlackConnectionSyncStateProviderTest extends BaseUnitTest {

    private static final long WS = 11L;
    private static final long CONNECTION_ID = 3L;
    private static final IntegrationRef REF = new IntegrationRef(IntegrationKind.SLACK, WS, "T123");

    @Mock
    private SlackMonitoredChannelRepository monitoredChannelRepository;

    @Mock
    private SlackMessageRepository messageRepository;

    private final SlackRateLimitTracker rateLimitTracker = new SlackRateLimitTracker(new SimpleMeterRegistry());

    private SlackConnectionSyncStateProvider provider = mock(SlackConnectionSyncStateProvider.class);

    private SlackConnectionSyncStateProvider providerWith(String cron) {
        return new SlackConnectionSyncStateProvider(
                monitoredChannelRepository,
                messageRepository,
                new SlackSyncProperties(cron, 10, 15, Duration.ZERO, true, 5, true),
                rateLimitTracker);
    }

    private void setUpDefault() {
        provider = providerWith("0 0 4 * * *");
    }

    /**
     * Slack must report {@code webhookRegistered = null} — "not tracked" — and never a TRUE derived from the
     * connection being ACTIVE. Slack's Events API subscription is declared once in the app manifest at the
     * app level, so no per-installation subscription id exists to observe (contrast GitHub's App install,
     * GitLab's stored webhook id, Outline's stored subscription id). Reading TRUE off an ACTIVE connection
     * would restate "the install exists" in a column the admin reads as "deliveries are wired up".
     */
    @Test
    void describe_webhookRegisteredIsNull_becauseSlackSubscriptionStateIsNotObservable() {
        setUpDefault();

        ConnectionSyncDetails details = provider.describe(REF, CONNECTION_ID);

        assertThat(details.webhookRegistered()).isNull();
        assertThat(details.rateLimit()).isNull();
        assertThat(details.backfill()).isNull();
        assertThat(details.vendorHealthDegraded()).isFalse();
    }

    /**
     * A Slack workspace that has never been throttled has no rate-limit fact to state, so the row stays
     * off the page — a consequence of "nothing observed", not "nothing tracked".
     */
    @Test
    void describe_neverThrottled_reportsNoRateLimit() {
        setUpDefault();

        assertThat(provider.describe(REF, CONNECTION_ID).rateLimit()).isNull();
    }

    /**
     * The non-Marketplace {@code conversations.history} clamp answers with {@code Retry-After: 60}; it
     * surfaces as a back-off deadline, never as a quota, because Slack reports no quota.
     */
    @Test
    void describe_afterObserved429_reportsThrottledUntilAndNoQuota() {
        setUpDefault();
        rateLimitTracker.recordThrottle(WS, 60_000L);

        var rateLimit = provider.describe(REF, CONNECTION_ID).rateLimit();

        assertThat(rateLimit).isNotNull();
        assertThat(rateLimit.throttledUntil()).isAfter(Instant.now().plusSeconds(50));
        assertThat(rateLimit.observedAt()).isNotNull();
        assertThat(rateLimit.limit()).isNull();
        assertThat(rateLimit.remaining()).isNull();
    }

    /** A throttle on a different workspace must not leak into this one's status. */
    @Test
    void describe_throttleOnAnotherWorkspace_isNotReportedHere() {
        setUpDefault();
        rateLimitTracker.recordThrottle(WS + 1, 60_000L);

        assertThat(provider.describe(REF, CONNECTION_ID).rateLimit()).isNull();
    }

    @Test
    void describe_nextScheduledSyncAt_isComputedFromTheCronExpression() {
        provider = providerWith("0 0 4 * * *");
        Instant before = Instant.now();

        ConnectionSyncDetails details = provider.describe(REF, CONNECTION_ID);

        // The cron fires daily at 04:00: assert the actual next occurrence, not merely "in the future"
        // (which any future instant, e.g. now+1s, would satisfy).
        Instant next = details.nextScheduledSyncAt();
        assertThat(next).isNotNull();
        ZonedDateTime fire = next.atZone(ZoneId.systemDefault());
        assertThat(fire.getHour()).isEqualTo(4);
        assertThat(fire.getMinute()).isZero();
        assertThat(fire.getSecond()).isZero();
        assertThat(next).isAfter(before).isBeforeOrEqualTo(before.plus(Duration.ofDays(1)));
    }

    @Test
    void describe_invalidCron_yieldsNullNextScheduledSyncAt() {
        provider = providerWith("not a cron");

        assertThat(provider.describe(REF, CONNECTION_ID).nextScheduledSyncAt()).isNull();
    }

    @Test
    void resources_mapsEachMonitoredChannelAndConvertsTheSlackTsWatermark() {
        setUpDefault();
        Instant syncedAt = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
        SlackMonitoredChannel channel = new SlackMonitoredChannel();
        channel.setId(101L);
        channel.setWorkspaceId(WS);
        channel.setSlackTeamId("T123");
        channel.setSlackChannelId("C1");
        channel.setChannelName("general");
        channel.setConsentState(ConsentState.ACTIVE);
        channel.setLastHistorySyncedTs(SlackTs.ofInstant(syncedAt));
        channel.setLastSyncError("History sync failed (SlackApiException)");

        when(monitoredChannelRepository.findByWorkspaceIdAndConsentStateNot(WS, ConsentState.REVOKED))
                .thenReturn(List.of(channel));
        when(messageRepository.countGroupedByChannelId(WS)).thenReturn(List.of(channelCount("C1", 42L)));

        List<SyncResourceState> resources = provider.resources(REF, CONNECTION_ID);

        assertThat(resources).hasSize(1);
        SyncResourceState resource = resources.get(0);
        assertThat(resource.id()).isEqualTo(101L);
        assertThat(resource.externalId()).isEqualTo("C1");
        assertThat(resource.name()).isEqualTo("general");
        assertThat(resource.type()).isEqualTo(SyncResourceState.Type.CHANNEL);
        assertThat(resource.state()).isEqualTo("ACTIVE");
        assertThat(resource.lastSyncedAt()).isEqualTo(syncedAt);
        assertThat(resource.itemCount()).isEqualTo(42L);
        assertThat(resource.upstreamCount()).isNull();
        assertThat(resource.lastError()).isEqualTo("History sync failed (SlackApiException)");
    }

    @Test
    void resources_channelWithoutADisplayName_fallsBackToTheSlackChannelId() {
        setUpDefault();
        SlackMonitoredChannel channel = new SlackMonitoredChannel();
        channel.setId(102L);
        channel.setWorkspaceId(WS);
        channel.setSlackTeamId("T123");
        channel.setSlackChannelId("C2");
        channel.setConsentState(ConsentState.PENDING);

        when(monitoredChannelRepository.findByWorkspaceIdAndConsentStateNot(WS, ConsentState.REVOKED))
                .thenReturn(List.of(channel));
        when(messageRepository.countGroupedByChannelId(WS)).thenReturn(List.of());

        List<SyncResourceState> resources = provider.resources(REF, CONNECTION_ID);

        assertThat(resources.get(0).name()).isEqualTo("C2");
        assertThat(resources.get(0).state()).isEqualTo("PENDING");
        assertThat(resources.get(0).lastSyncedAt()).isNull();
        assertThat(resources.get(0).itemCount()).isEqualTo(0L);
    }

    private SlackMessageRepository.ChannelItemCount channelCount(String channelId, long count) {
        return new SlackMessageRepository.ChannelItemCount() {
            @Override
            public String getSlackChannelId() {
                return channelId;
            }

            @Override
            public Long getItemCount() {
                return count;
            }
        };
    }
}
