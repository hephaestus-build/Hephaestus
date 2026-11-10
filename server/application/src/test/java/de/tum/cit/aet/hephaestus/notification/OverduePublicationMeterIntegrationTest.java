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

    private void publication(String listenerId, Instant publishedAt, String status) {
        jdbc.update(
                """
            INSERT INTO event_publication
                (id, listener_id, event_type, serialized_event, publication_date, status, completion_attempts)
            VALUES (?, ?, ?, ?, ?, ?, 1)
            """,
                UUID.randomUUID(),
                listenerId,
                // An existing type, never deserialized: the meter reads only the listener id.
                BotCommandReceivedEvent.class.getName(),
                "{}",
                Timestamp.from(publishedAt),
                status);
    }
}
