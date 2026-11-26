package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.events.BotCommandReceivedEvent;
import de.tum.cit.aet.hephaestus.notification.metrics.NotificationMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class OverduePublicationMeterIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OverduePublicationMeter meter;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void shouldCountOnlyPublicationsOpenPastTheBoundWhenMeasuringOverdue() {
        String type = "OverdueProbe" + UUID.randomUUID().toString().replace("-", "");
        String listenerId = "de.example." + type + ".on(de.example.ProbeEvent)";
        Instant now = Instant.now();
        try {
            publication(listenerId, now.minus(Duration.ofHours(2)), "FAILED");
            publication(listenerId, now.minus(Duration.ofHours(3)), "PROCESSING");
            publication(listenerId, now.minus(Duration.ofMinutes(10)), "FAILED");

            meter.measure();
        } finally {
            // No listener has this id, so a later redelivery sweep must not find these rows.
            jdbc.update("DELETE FROM event_publication WHERE listener_id = ?", listenerId);
        }

        Gauge gauge = meterRegistry
                .get(NotificationMetrics.EVENT_PUBLICATIONS_OVERDUE)
                .tag("listener", type + ".on")
                .gauge();
        assertThat(gauge.value()).isEqualTo(2.0);
    }

    @Test
    void shouldCountAnExhaustedPublicationApartFromTheOverdueOnes() {
        String type = "ExhaustedProbe" + UUID.randomUUID().toString().replace("-", "");
        String listenerId = "de.example." + type + ".on(de.example.ProbeEvent)";
        Instant twoHoursAgo = Instant.now().minus(Duration.ofHours(2));
        try {
            publication(listenerId, twoHoursAgo, "FAILED", NotificationRedeliveryJob.MAX_ATTEMPTS);
            // Exhausted at once, though younger than the overdue bound.
            publication(
                    listenerId,
                    Instant.now().minus(Duration.ofMinutes(10)),
                    "FAILED",
                    NotificationRedeliveryJob.MAX_ATTEMPTS);
            // Exhausted too, though an upgrade removed its event type: the count reads no payload.
            publication(
                    listenerId,
                    Instant.now().minus(Duration.ofMinutes(10)),
                    "FAILED",
                    NotificationRedeliveryJob.MAX_ATTEMPTS,
                    "de.example.RemovedEvent");
            publication(listenerId, twoHoursAgo, "FAILED", NotificationRedeliveryJob.MAX_ATTEMPTS - 1);
            // A last attempt that still runs can end: it is not exhausted yet.
            publication(listenerId, twoHoursAgo, "PROCESSING", NotificationRedeliveryJob.MAX_ATTEMPTS);

            meter.measure();
        } finally {
            jdbc.update("DELETE FROM event_publication WHERE listener_id = ?", listenerId);
        }

        assertThat(meterRegistry
                        .get(NotificationMetrics.EVENT_PUBLICATIONS_EXHAUSTED)
                        .tag("listener", type + ".on")
                        .gauge()
                        .value())
                .isEqualTo(3.0);
        assertThat(meterRegistry
                        .get(NotificationMetrics.EVENT_PUBLICATIONS_OVERDUE)
                        .tag("listener", type + ".on")
                        .gauge()
                        .value())
                .as("the old publication with attempts left, and the one whose last attempt runs")
                .isEqualTo(2.0);
    }

    private void publication(String listenerId, Instant publishedAt, String status) {
        publication(listenerId, publishedAt, status, 1);
    }

    private void publication(String listenerId, Instant publishedAt, String status, int attempts) {
        // An existing type, never deserialized: the meter reads only the listener id.
        publication(listenerId, publishedAt, status, attempts, BotCommandReceivedEvent.class.getName());
    }

    private void publication(String listenerId, Instant publishedAt, String status, int attempts, String eventType) {
        jdbc.update("""
            INSERT INTO event_publication
                (id, listener_id, event_type, serialized_event, publication_date, status, completion_attempts)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), listenerId, eventType, "{}", Timestamp.from(publishedAt), status, attempts);
    }
}
