package de.tum.cit.aet.hephaestus.core.auth.export;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.notification.NotificationRedeliveryJob;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("Account export across an interrupted generation")
class AccountExportDurabilityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AccountExportService exports;

    @Autowired
    private ExportGenerationWorker worker;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private FailedEventPublications failedPublications;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldGenerateTheExportWhenTheRegistryResubmitsAnInterruptedGeneration() {
        long accountId = Objects.requireNonNull(
                accounts.save(new Account("Interrupted export subject")).getId());
        long[] exportId = new long[1];
        Map<String, @Nullable Object> owed =
                Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> {
                    exportId[0] = Objects.requireNonNull(
                            exports.requestExport(accountId).getId());
                    // The owed generation commits with the PENDING row, before any listener runs.
                    return jdbc.queryForMap(
                            "SELECT listener_id, event_type, serialized_event FROM event_publication"
                                    + " WHERE serialized_event LIKE ?",
                            publicationOf(exportId[0]));
                }));
        assertThat(status(exportId[0])).isEqualTo("READY");

        // The state a stopped process leaves: the export still pending, its publication marked FAILED by the staleness
        // monitor and older than the redelivery job's minimum age.
        jdbc.update(
                "UPDATE account_export SET status = 'PENDING', payload = NULL, completed_at = NULL, expires_at = NULL"
                        + " WHERE id = ?",
                exportId[0]);
        jdbc.update(
                "INSERT INTO event_publication"
                        + " (id, listener_id, event_type, serialized_event, publication_date, status, completion_attempts)"
                        + " VALUES (?, ?, ?, ?, ?, 'FAILED', 1)",
                UUID.randomUUID(),
                owed.get("listener_id"),
                owed.get("event_type"),
                owed.get("serialized_event"),
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))));

        new NotificationRedeliveryJob(failedPublications).resubmitFailed();

        assertThat(status(exportId[0])).isEqualTo("READY");
        assertThat(jdbc.queryForList(
                        "SELECT id FROM event_publication WHERE serialized_event LIKE ?", publicationOf(exportId[0])))
                .isEmpty();
    }

    @Test
    void shouldLeaveAReadyExportUnchangedWhenItsGenerationIsDeliveredAgain() {
        long accountId = Objects.requireNonNull(
                accounts.save(new Account("Redelivered export subject")).getId());
        long exportId = Objects.requireNonNull(exports.requestExport(accountId).getId());
        assertThat(status(exportId)).isEqualTo("READY");
        Object completedAt =
                jdbc.queryForObject("SELECT completed_at FROM account_export WHERE id = ?", Object.class, exportId);

        worker.on(new AccountExportRequested(exportId, accountId));

        assertThat(status(exportId)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT completed_at FROM account_export WHERE id = ?", Object.class, exportId))
                .isEqualTo(completedAt);
    }

    private String status(long exportId) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT status FROM account_export WHERE id = ?", String.class, exportId));
    }

    private static String publicationOf(long exportId) {
        return "%\"exportId\":" + exportId + ",%";
    }
}
