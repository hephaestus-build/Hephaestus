package de.tum.cit.aet.hephaestus.core.auth.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.notification.NotificationRedeliveryJob;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@DisplayName("Account export across an interrupted generation")
class AccountExportDurabilityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AccountExportService exports;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private FailedEventPublications failedPublications;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExportGenerationWorker worker;

    @MockitoSpyBean
    private ExportBundleAssembler assembler;

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

    @Test
    void shouldGenerateTheExportWhenTheRegistryResubmitsAnInterruptedGeneration() {
        long accountId = Objects.requireNonNull(
                accounts.save(new Account("Interrupted export subject")).getId());
        // A process that stops during generation rolls the generation back and never completes its publication.
        doThrow(new ProcessStopped()).when(assembler).assemble(accountId);

        long exportId = Objects.requireNonNull(exports.requestExport(accountId).getId());

        assertThat(status(exportId)).isEqualTo("PENDING");
        doCallRealMethod().when(assembler).assemble(accountId);
        // The staleness monitor marks the abandoned publication FAILED; the redelivery job takes it after its minimum
        // age.
        assertThat(jdbc.update(
                        "UPDATE event_publication SET status = 'FAILED', publication_date = ? WHERE serialized_event LIKE ?",
                        Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))),
                        publicationOf(exportId)))
                .as("the request transaction must record the owed generation")
                .isEqualTo(1);

        new NotificationRedeliveryJob(failedPublications).resubmitFailed();

        assertThat(status(exportId)).isEqualTo("READY");
        assertThat(jdbc.queryForList(
                        "SELECT id FROM event_publication WHERE serialized_event LIKE ?", publicationOf(exportId)))
                .isEmpty();
    }

    private String status(long exportId) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT status FROM account_export WHERE id = ?", String.class, exportId));
    }

    private static String publicationOf(long exportId) {
        return "%\"exportId\":" + exportId + ",%";
    }

    private static final class ProcessStopped extends Error {
        private static final long serialVersionUID = 1L;
    }
}
