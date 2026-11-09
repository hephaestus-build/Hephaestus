package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.notification.RegistryProbeConfiguration.ProbeEvent;
import de.tum.cit.aet.hephaestus.notification.RegistryProbeConfiguration.ProbeListener;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventPublicationRepository.FailedCriteria;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The redelivery sweep's fair order ({@link NotificationPublicationConfiguration}): a batch of failed publications
 * that keeps failing, or that only expires, never starves a later one. Listeners run inline here.
 */
@Import(RegistryProbeConfiguration.class)
class NotificationPublicationFairnessIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ProbeListener probe;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private EventPublicationRepository publicationRepository;

    @Autowired
    private FailedEventPublications failedPublications;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void stopFailing() {
        probe.failing(false);
    }

    @Test
    void shouldDrainAnExpiredBatchBeforeDeliveringTheNextPublication() {
        String key = "backlog-" + UUID.randomUUID();
        UUID source = failedPublication(key);
        List<UUID> expiredIds = new ArrayList<>();
        try {
            for (int i = 0; i < NotificationRedeliveryJob.BATCH_SIZE; i++) {
                expiredIds.add(copy(
                        source,
                        new ProbeEvent("expired-" + UUID.randomUUID(), true),
                        Instant.now().minus(Duration.ofDays(8)),
                        "FAILED"));
            }
            jdbc.update(
                    "UPDATE event_publication SET publication_date = ? WHERE id = ?",
                    Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))),
                    source);
            probe.failing(false);

            new NotificationRedeliveryJob(failedPublications).resubmitFailed();
            assertThat(probe.delivered()).doesNotContain(key);
            new NotificationRedeliveryJob(failedPublications).resubmitFailed();

            assertThat(probe.delivered()).containsOnlyOnce(key);
            assertThat(publications(key)).isEmpty();
            for (UUID id : expiredIds) {
                assertThat(jdbc.queryForList("SELECT id FROM event_publication WHERE id = ?", id))
                        .isEmpty();
            }
        } finally {
            for (UUID id : expiredIds) jdbc.update("DELETE FROM event_publication WHERE id = ?", id);
        }
    }

    @Test
    void shouldAdvancePastAStillFailingBatchOnTheNextRetry() {
        String key = "fair-retry-" + UUID.randomUUID();
        UUID newest = failedPublication(key);
        List<UUID> older = new ArrayList<>();
        try {
            jdbc.update(
                    "UPDATE event_publication SET publication_date = ? WHERE id = ?",
                    Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))),
                    newest);
            for (int i = 0; i < NotificationRedeliveryJob.BATCH_SIZE; i++) {
                older.add(copy(
                        newest,
                        new ProbeEvent("older-" + UUID.randomUUID(), false),
                        Instant.now().minus(Duration.ofHours(1)).plusSeconds(i),
                        "FAILED"));
            }
            new NotificationRedeliveryJob(failedPublications).resubmitFailed();
            for (UUID id : older) {
                assertThat(jdbc.queryForMap(
                                "SELECT status, last_resubmission_date FROM event_publication WHERE id = ?", id))
                        .containsEntry("status", "FAILED")
                        .hasEntrySatisfying(
                                "last_resubmission_date",
                                value -> assertThat(value).isNotNull());
            }
            assertThat(publicationRepository.findFailedPublications(FailedCriteria.ALL))
                    .extracting(TargetEventPublication::getIdentifier)
                    .containsAll(older)
                    .contains(newest);
            var criteria = FailedCriteria.ALL
                    .withPublicationsPublishedBefore(Instant.now().minus(Duration.ofMinutes(5)))
                    .withItemsToRead(1);
            assertThat(publicationRepository.findFailedPublications(criteria))
                    .extracting(TargetEventPublication::getIdentifier)
                    .containsExactly(newest);

            probe.failing(false);
            failedPublications.resubmit(
                    ResubmissionOptions.defaults().withBatchSize(1).withMaxInFlight(1));

            assertThat(probe.delivered()).containsOnlyOnce(key);
            assertThat(publications(key)).isEmpty();
        } finally {
            for (UUID id : older) jdbc.update("DELETE FROM event_publication WHERE id = ?", id);
            jdbc.update("DELETE FROM event_publication WHERE id = ?", newest);
        }
    }

    @Test
    void shouldApplyFailedStatusAndMinimumAgeBeforeTheFairBatchLimit() {
        UUID source = failedPublication("retry-filter-" + UUID.randomUUID());
        List<UUID> other = new ArrayList<>();
        try {
            jdbc.update(
                    "UPDATE event_publication SET publication_date = ? WHERE id = ?",
                    Timestamp.from(Instant.parse("2001-01-01T00:00:00Z")),
                    source);
            for (String status : List.of("PUBLISHED", "PROCESSING", "RESUBMITTED", "COMPLETED", "FAILED")) {
                Instant date = status.equals("FAILED")
                        ? Instant.now().plus(Duration.ofDays(1))
                        : Instant.parse("2000-01-01T00:00:00Z");
                other.add(copy(source, new ProbeEvent("other-" + UUID.randomUUID(), false), date, status));
            }
            assertThat(publicationRepository.findFailedPublications(FailedCriteria.ALL
                            .withPublicationsPublishedBefore(Instant.now().minus(Duration.ofMinutes(5)))
                            .withItemsToRead(1)))
                    .extracting(TargetEventPublication::getIdentifier)
                    .containsExactly(source);
            assertThat(publicationRepository.findFailedPublications(FailedCriteria.ALL))
                    .extracting(TargetEventPublication::getIdentifier)
                    .contains(source, other.getLast());
        } finally {
            for (UUID id : other) jdbc.update("DELETE FROM event_publication WHERE id = ?", id);
            jdbc.update("DELETE FROM event_publication WHERE id = ?", source);
        }
    }

    /** Publishes a probe while the probe fails, and returns its failed publication. */
    private UUID failedPublication(String key) {
        probe.failing(true);
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> events.publishEvent(new ProbeEvent(key, false)));
        return (UUID) Objects.requireNonNull(publications(key).getFirst().get("id"));
    }

    /** A publication for the same listener as {@code source}, carrying {@code event}. */
    private UUID copy(UUID source, ProbeEvent event, Instant publishedAt, String status) {
        UUID id = UUID.randomUUID();
        assertThat(jdbc.update(
                        """
                        INSERT INTO event_publication
                            (id, listener_id, event_type, serialized_event, publication_date, status, completion_attempts)
                        SELECT ?, listener_id, event_type, ?, ?, ?, 1
                          FROM event_publication WHERE id = ?
                        """, id, objectMapper.writeValueAsString(event), Timestamp.from(publishedAt), status, source))
                .isEqualTo(1);
        return id;
    }

    private List<Map<String, @Nullable Object>> publications(String key) {
        return jdbc.queryForList(
                "SELECT id, status FROM event_publication WHERE serialized_event LIKE ?", "%\"key\":\"" + key + "\"%");
    }
}
