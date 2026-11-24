package de.tum.cit.aet.hephaestus.core.auth.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import de.tum.cit.aet.hephaestus.core.auth.AccountPurger;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonAct;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonActFixture;
import de.tum.cit.aet.hephaestus.notification.NotificationRedeliveryJob;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@DisplayName("Account export across an interrupted generation")
class AccountExportDurabilityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AccountExportService exports;

    @Autowired
    private ExportGenerationWorker worker;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountPurger purger;

    @Autowired
    private FailedEventPublications failedPublications;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private final JdbcTemplate fixture = PostgreSQLTestContainer.ownerJdbc();

    @Test
    void shouldRollBackTheExportAuditAndPublicationWithTheRequest() {
        long accountId = Objects.requireNonNull(
                accounts.save(new Account("Export rolled back")).getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            exports.requestExport(PersonActFixture.self(accountId));
            assertThat(publications(accountId)).hasSize(1);
            tx.setRollbackOnly();
        });

        assertExportsGone(accountId);
        assertThat(publications(accountId)).isEmpty();
        assertThat(fixture.queryForList(
                        "SELECT 1 FROM auth_event WHERE account_id = ? AND event_type = 'EXPORT_REQUESTED'",
                        Integer.class,
                        accountId))
                .isEmpty();
    }

    @Test
    void shouldRefuseTheAuthenticatedRequestWhenErasureCommitsBeforeExportAdmission() throws Exception {
        long accountId = Objects.requireNonNull(
                accounts.save(new Account("Erasure first")).getId());
        var authenticated = new CompletableFuture<Void>();
        var erased = new CompletableFuture<Void>();
        try (var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var request = requests.submit(() -> {
                var transaction = new TransactionTemplate(transactionManager);
                assertThatExceptionOfType(ResponseStatusException.class)
                        .isThrownBy(() -> transaction.executeWithoutResult(tx -> {
                            PersonAct act = authenticatedAct(accountId);
                            authenticated.complete(null);
                            erased.orTimeout(30, TimeUnit.SECONDS).join();
                            exports.requestExport(act);
                        }))
                        .satisfies(e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
            });
            try {
                authenticated.get(30, TimeUnit.SECONDS);
                purger.purge(accountId);
                assertThat(accounts.findById(accountId).orElseThrow().getStatus())
                        .isEqualTo(Account.Status.DELETED);
            } finally {
                erased.complete(null);
            }
            request.get(30, TimeUnit.SECONDS);
        }
        assertExportsGone(accountId);
        assertThat(publications(accountId)).isEmpty();
    }

    @Test
    void shouldOrderErasureAfterAnAdmittedExportAndPreventItsRedeliveryFromCreatingABundle() throws Exception {
        long accountId = Objects.requireNonNull(
                accounts.save(new Account("Export admission first")).getId());
        PersonAct act = authenticatedAct(accountId);
        long readyId = Objects.requireNonNull(exports.requestExport(act).getId());
        assertThat(exports.downloadPayload(readyId, act)).isPresent();
        var generation = new CompletableFuture<AccountExportRequested>();
        try (var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var admission = HeldTransaction.holdIn(new TransactionTemplate(transactionManager), jdbc, () -> {
                long exportId =
                        Objects.requireNonNull(exports.requestExport(act).getId());
                assertThat(publications(accountId)).hasSize(1);
                generation.complete(new AccountExportRequested(exportId, accountId));
            });
            var erasure = CompletableFuture.runAsync(() -> purger.purge(accountId), requests);
            admission.commitAfter(erasure, () -> assertThat(erasure).isNotDone());
            erasure.get(30, TimeUnit.SECONDS);
        }

        assertThat(accounts.findById(accountId).orElseThrow().getStatus()).isEqualTo(Account.Status.DELETED);
        assertExportsGone(accountId);
        worker.on(generation.join());
        assertExportsGone(accountId);
        assertThat(publications(accountId)).isEmpty();
    }

    @Test
    void shouldGenerateTheExportWhenTheRegistryResubmitsAnInterruptedGeneration() {
        long accountId = Objects.requireNonNull(
                accounts.save(new Account("Interrupted export subject")).getId());
        long[] exportId = new long[1];
        Map<String, @Nullable Object> owed =
                Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> {
                    exportId[0] = Objects.requireNonNull(exports.requestExport(PersonActFixture.self(accountId))
                            .getId());
                    // The owed generation commits with the PENDING row, before any listener runs.
                    return jdbc.queryForMap(
                            "SELECT listener_id, event_type, serialized_event FROM event_publication"
                                    + " WHERE serialized_event LIKE ?",
                            publicationOf(exportId[0]));
                }));
        assertThat(status(exportId[0])).isEqualTo("READY");

        // The state a stopped process leaves: the export still pending, its publication marked FAILED by the staleness
        // monitor and older than the redelivery job's minimum age.
        fixture.update(
                "UPDATE account_export SET status = 'PENDING', payload = NULL, completed_at = NULL, expires_at = NULL"
                        + " WHERE id = ?",
                exportId[0]);
        jdbc.update(
                "INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date,"
                        + " status, completion_attempts) VALUES (?, ?, ?, ?, ?, 'FAILED', 1)",
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
        long exportId = Objects.requireNonNull(
                exports.requestExport(PersonActFixture.self(accountId)).getId());
        assertThat(status(exportId)).isEqualTo("READY");
        Object completedAt =
                fixture.queryForObject("SELECT completed_at FROM account_export WHERE id = ?", Object.class, exportId);

        worker.on(new AccountExportRequested(exportId, accountId));

        assertThat(status(exportId)).isEqualTo("READY");
        assertThat(fixture.queryForObject(
                        "SELECT completed_at FROM account_export WHERE id = ?", Object.class, exportId))
                .isEqualTo(completedAt);
    }

    private String status(long exportId) {
        return Objects.requireNonNull(
                fixture.queryForObject("SELECT status FROM account_export WHERE id = ?", String.class, exportId));
    }

    private static PersonAct authenticatedAct(long accountId) {
        var context = SecurityContextHolder.createEmptyContext();
        var jwt = Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject(Long.toString(accountId))
                .jti(UUID.randomUUID().toString())
                .build();
        context.setAuthentication(new JwtAuthenticationToken(jwt));
        SecurityContextHolder.setContext(context);
        try {
            return PersonAct.fromPrincipal();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private List<@Nullable UUID> publications(long accountId) {
        return jdbc.queryForList("""
                SELECT id FROM event_publication
                WHERE event_type = ? AND serialized_event::jsonb ->> 'accountId' = ?
                """, UUID.class, AccountExportRequested.class.getName(), Long.toString(accountId));
    }

    private void assertExportsGone(long accountId) {
        assertThat(fixture.queryForList("SELECT id FROM account_export WHERE account_id = ?", Long.class, accountId))
                .isEmpty();
    }

    private static String publicationOf(long exportId) {
        return "%\"exportId\":" + exportId + ",%";
    }
}
