package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class DeliveryInboxRetentionTest extends BaseUnitTest {
    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private final DeliveryInbox inbox = mock(DeliveryInbox.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void shouldExposeCleanupFailuresWithoutPreventingTheNextScheduledRun() throws Exception {
        var retention = retention(broker(Duration.ofDays(7)));
        doThrow(new IllegalStateException("database unavailable"))
                .doNothing()
                .when(inbox)
                .deleteExpired(any(Instant.class));
        retention.prune();
        retention.prune();
        assertThat(failures()).isEqualTo(1);
    }

    @Test
    void shouldRetainKeysBeyondALongerLiveAgeLimitWhenTheReductionWasWithheld() throws Exception {
        retention(broker(Duration.ofDays(30))).prune();
        verify(inbox).deleteExpired(NOW.minus(Duration.ofDays(31)));
        assertThat(failures()).isZero();
    }

    @Test
    void shouldKeepKeysWhenTheBrokerCannotConfirmItsRetentionBound() throws Exception {
        var connection = mock(Connection.class);
        when(connection.jetStreamManagement()).thenThrow(new IOException("not reachable"));
        retention(connection).prune();
        verify(inbox, never()).deleteExpired(any());
        assertThat(failures()).isEqualTo(1);
    }

    @Test
    void shouldKeepKeysWhenALiveStreamHasNoFiniteAgeLimit() throws Exception {
        retention(broker(Duration.ZERO)).prune();
        verify(inbox, never()).deleteExpired(any());
        assertThat(failures()).isEqualTo(1);
    }

    @Test
    void shouldPruneUsingRemainingBoundsWhenAStreamDoesNotExist() throws Exception {
        var connection = broker(Duration.ofDays(30));
        var missing = mock(JetStreamApiException.class);
        when(missing.getErrorCode()).thenReturn(404);
        when(connection.jetStreamManagement().getStreamInfo("slack")).thenThrow(missing);
        retention(connection).prune();
        verify(inbox).deleteExpired(NOW.minus(Duration.ofDays(31)));
        assertThat(failures()).isZero();
    }

    @Test
    void shouldKeepKeysWhenTheBrokerRejectsTheStreamRead() throws Exception {
        var connection = mock(Connection.class);
        var management = mock(JetStreamManagement.class);
        var unavailable = mock(JetStreamApiException.class);
        when(unavailable.getErrorCode()).thenReturn(503);
        when(connection.jetStreamManagement()).thenReturn(management);
        when(management.getStreamInfo(anyString())).thenThrow(unavailable);
        retention(connection).prune();
        verify(inbox, never()).deleteExpired(any());
        assertThat(failures()).isEqualTo(1);
    }

    @Test
    void shouldSkipCleanupWhenNatsIsDisabled() {
        new DeliveryInboxRetention(inbox, mock(WebhookProperties.class), Clock.fixed(NOW, ZoneOffset.UTC), meters, null)
                .prune();
        verify(inbox, never()).deleteExpired(any());
        assertThat(failures()).isZero();
    }

    private double failures() {
        return meters.get(IntegrationCoreMetrics.INBOX_RETENTION_FAILURES)
                .counter()
                .count();
    }

    private DeliveryInboxRetention retention(@Nullable Connection connection) {
        var properties = mock(WebhookProperties.class);
        var stream = mock(WebhookProperties.Stream.class);
        when(properties.stream()).thenReturn(stream);
        when(stream.maxAge()).thenReturn(Duration.ofDays(7));
        when(stream.maxAgeByStream()).thenReturn(Map.of());
        return new DeliveryInboxRetention(inbox, properties, Clock.fixed(NOW, ZoneOffset.UTC), meters, connection);
    }

    private static Connection broker(Duration maxAge) throws Exception {
        var connection = mock(Connection.class);
        var management = mock(JetStreamManagement.class);
        var info = mock(StreamInfo.class);
        when(connection.jetStreamManagement()).thenReturn(management);
        when(management.getStreamInfo(anyString())).thenReturn(info);
        var configuration =
                StreamConfiguration.builder().name("github").maxAge(maxAge).build();
        when(info.getConfiguration()).thenReturn(configuration);
        return connection;
    }
}
