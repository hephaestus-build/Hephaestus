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
                    "UPDATE event_publication SET publication_date = ?, last_resubmission_date = ? WHERE id = ?",
                    Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))),
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
                    "UPDATE event_publication SET publication_date = ?, last_resubmission_date = ? WHERE id = ?",
                    Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))),
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
                    .as("a publication just retried waits for its backoff")
                    .doesNotContainAnyElementsOf(older)
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
                    "UPDATE event_publication SET publication_date = ?, last_resubmission_date = ? WHERE id = ?",
                    Timestamp.from(Instant.parse("2001-01-01T00:00:00Z")),
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
                    .as("without a minimum age, the backoff still holds a publication from the future")
                    .contains(source)
                    .doesNotContain(other.getLast());
        } finally {
            for (UUID id : other) jdbc.update("DELETE FROM event_publication WHERE id = ?", id);
            jdbc.update("DELETE FROM event_publication WHERE id = ?", source);
        }
    }

    @Test
    void shouldLeaveAnExhaustedBatchOutOfTheSelectionSoTheNextPublicationIsDelivered() {
        String key = "after-exhausted-" + UUID.randomUUID();
        UUID next = failedPublication(key);
        List<UUID> exhausted = new ArrayList<>();
        try {
            jdbc.update(
                    "UPDATE event_publication SET publication_date = ?, last_resubmission_date = ? WHERE id = ?",
                    Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))),
                    Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))),
                    next);
            for (int i = 0; i < NotificationRedeliveryJob.BATCH_SIZE; i++) {
                UUID id = copy(
                        next,
                        new ProbeEvent("exhausted-" + UUID.randomUUID(), false),
                        Instant.now().minus(Duration.ofHours(2)).plusSeconds(i),
                        "FAILED");
                jdbc.update(
                        "UPDATE event_publication SET completion_attempts = ? WHERE id = ?",
                        NotificationRedeliveryJob.MAX_ATTEMPTS,
                        id);
                exhausted.add(id);
            }
            probe.failing(false);

            new NotificationRedeliveryJob(failedPublications).resubmitFailed();

            assertThat(probe.delivered()).containsOnlyOnce(key);
            assertThat(publications(key)).isEmpty();
            for (UUID id : exhausted) {
                assertThat(jdbc.queryForMap(
                                "SELECT status, completion_attempts, last_resubmission_date FROM event_publication"
                                        + " WHERE id = ?",
                                id))
                        .as("an exhausted publication stays as the record of its failure, never resubmitted")
                        .containsEntry("status", "FAILED")
                        .containsEntry("completion_attempts", NotificationRedeliveryJob.MAX_ATTEMPTS)
                        .containsEntry("last_resubmission_date", null);
            }
        } finally {
            for (UUID id : exhausted) jdbc.update("DELETE FROM event_publication WHERE id = ?", id);
        }
    }

    @Test
    void shouldSpendOneAttemptPerBackoffWhenTwoServersRunTheirPassesBackToBack() {
        String key = "two-servers-" + UUID.randomUUID();
        UUID failed = failedPublication(key);
        try {
            jdbc.update(
                    "UPDATE event_publication SET publication_date = ?, last_resubmission_date = ? WHERE id = ?",
                    Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))),
                    Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))),
                    failed);

            // One server's pass, then the other's a moment later: both find the publication older than the minimum age.
            new NotificationRedeliveryJob(failedPublications).resubmitFailed();
            new NotificationRedeliveryJob(failedPublications).resubmitFailed();

            assertThat(attempts(failed))
                    .as("the second pass waits for the backoff of the first")
                    .isEqualTo(2);

            // The backoff after the second attempt is ten minutes, counted from that attempt.
            jdbc.update(
                    "UPDATE event_publication SET last_resubmission_date = ? WHERE id = ?",
                    Timestamp.from(Instant.now().minus(Duration.ofMinutes(9))),
                    failed);
            new NotificationRedeliveryJob(failedPublications).resubmitFailed();
            assertThat(attempts(failed)).isEqualTo(2);

            jdbc.update(
                    "UPDATE event_publication SET last_resubmission_date = ? WHERE id = ?",
                    Timestamp.from(Instant.now().minus(Duration.ofMinutes(11))),
                    failed);
            new NotificationRedeliveryJob(failedPublications).resubmitFailed();
            assertThat(attempts(failed)).isEqualTo(3);
        } finally {
            jdbc.update("DELETE FROM event_publication WHERE id = ?", failed);
        }
    }

    private int attempts(UUID publication) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT completion_attempts FROM event_publication WHERE id = ?", Integer.class, publication));
    }

    /**
     * Publishes a probe while the probe fails, and returns its failed publication. Modulith sets the last resubmission
     * of a first failure to its publication time, so a test that ages a publication ages both.
     */
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
