package de.tum.cit.aet.hephaestus.core.auth.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.auth.AccountPurger;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountConversationExport;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountFeedbackExport;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class ExportGenerationWorkerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AccountExportRepository repository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountPurger purger;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OwnerScope ownerScope;

    @Autowired
    private Clock clock;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountConversationExport conversations;

    @Autowired
    private AccountFeedbackExport feedback;

    @ParameterizedTest
    @EnumSource(names = {"DELETING", "DELETED"})
    void shouldStopQueuedGenerationWhileErasureHoldsTheAccountLock(Account.Status status) throws Exception {
        long accountId = Objects.requireNonNull(
                accountRepository.save(new Account("Inactive export subject")).getId());
        long exportId = seedExport(accountId);
        setAccountStatus(accountId, status);
        var assembler = mock(ExportBundleAssembler.class);
        var registry = new SimpleMeterRegistry();

        var erasure = HeldTransaction.holdIn(
                new TransactionTemplate(transactionManager),
                jdbc,
                () -> accountRepository.lockStatusForUpdate(accountId));
        var generation =
                CompletableFuture.runAsync(() -> worker(assembler, registry).generate(exportId, accountId));
        // The worker can finish while erasure holds the account lock.
        erasure.commitAfter(generation, () -> assertThat(generation).isDone());
        generation.get(30, TimeUnit.SECONDS);

        verifyNoInteractions(assembler);
        assertFailureCommitted(exportId, accountId, registry, "account_unavailable", 0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldWithholdThePayloadWhenErasureCommitsDuringAssembly(boolean purge) throws Exception {
        long accountId = Objects.requireNonNull(accountRepository
                .save(new Account("Export publication subject"))
                .getId());
        long exportId = seedExport(accountId);
        var registry = new SimpleMeterRegistry();

        duringAssembly(exportId, accountId, registry, () -> {
            if (purge) {
                purger.purge(accountId);
            } else {
                setAccountStatus(accountId, Account.Status.DELETING);
            }
        });

        if (purge) {
            assertThat(PostgreSQLTestContainer.ownerJdbc()
                            .queryForList("SELECT id FROM account_export WHERE id = ?", Long.class, exportId))
                    .isEmpty();
        } else {
            assertFailureCommitted(exportId, accountId, registry, "account_unavailable", 0);
        }
    }

    @Test
    void shouldLeaveThePublicationOutstandingWhenAnotherDeliveryIsAssembling() throws Exception {
        long accountId = Objects.requireNonNull(accountRepository
                .save(new Account("Concurrent export delivery"))
                .getId());
        long exportId = seedExport(accountId);
        var registry = new SimpleMeterRegistry();

        duringAssembly(
                exportId,
                accountId,
                registry,
                () -> assertThatIllegalStateException()
                        .isThrownBy(() -> worker(mock(ExportBundleAssembler.class), registry)
                                .generate(exportId, accountId))
                        .withMessage("Export generation is already in progress."));

        assertThat(readExport(exportId, accountId).getStatus()).isEqualTo(AccountExport.Status.READY);
    }

    @Test
    void shouldLeaveThePublicationOutstandingWhenTheLifecycleLockTimesOut() throws Exception {
        long accountId = Objects.requireNonNull(
                accountRepository.save(new Account("Export publication lock")).getId());
        long exportId = seedExport(accountId);
        var assemblyStarted = new CompletableFuture<Void>();
        var resume = new CompletableFuture<Void>();
        var assembler = mock(ExportBundleAssembler.class);
        when(assembler.assemble(accountId)).thenAnswer(invocation -> {
            jdbc.execute("SET LOCAL lock_timeout = '1ms'");
            assemblyStarted.complete(null);
            resume.get(30, TimeUnit.SECONDS);
            return bundle(accountId);
        });
        var registry = new SimpleMeterRegistry();
        var worker = worker(assembler, registry);

        try (var jobs = Executors.newVirtualThreadPerTaskExecutor()) {
            var generation = jobs.submit(() -> worker.generate(exportId, accountId));
            try {
                assemblyStarted.get(30, TimeUnit.SECONDS);
                new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    accountRepository.lockStatusForUpdate(accountId);
                    resume.complete(null);
                    assertThatThrownBy(() -> generation.get(30, TimeUnit.SECONDS))
                            .rootCause()
                            .isInstanceOfSatisfying(
                                    SQLException.class,
                                    sql -> assertThat(sql.getSQLState()).isEqualTo("55P03"));
                });
            } finally {
                resume.complete(null);
            }
        }

        assertThat(readExport(exportId, accountId).getStatus()).isEqualTo(AccountExport.Status.PENDING);
        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_generation", "outcome", "failure")
                        .counter()
                        .count())
                .isZero();
        worker.generate(exportId, accountId);
        assertThat(readExport(exportId, accountId).getStatus()).isEqualTo(AccountExport.Status.READY);
    }

    private void duringAssembly(long exportId, long accountId, SimpleMeterRegistry registry, Runnable concurrent)
            throws Exception {
        var assembling = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var assembler = mock(ExportBundleAssembler.class);
        when(assembler.assemble(accountId)).thenAnswer(invocation -> {
            var bundle = bundle(accountId);
            assembling.complete(null);
            release.get(30, TimeUnit.SECONDS);
            return bundle;
        });
        try (var jobs = Executors.newVirtualThreadPerTaskExecutor()) {
            var generation = jobs.submit(() -> worker(assembler, registry).generate(exportId, accountId));
            try {
                assembling.get(30, TimeUnit.SECONDS);
                concurrent.run();
            } finally {
                release.complete(null);
            }
            generation.get(30, TimeUnit.SECONDS);
        }
        verify(assembler).assemble(accountId);
    }

    @Test
    void shouldCommitFailureWhenTheAssemblyTransactionIsAborted() {
        Long accountId = Objects.requireNonNull(
                accountRepository.save(new Account("Failed export subject")).getId());
        Long exportId = seedExport(accountId);
        ExportBundleAssembler assembler = mock(ExportBundleAssembler.class);
        when(assembler.assemble(accountId)).thenAnswer(invocation -> {
            jdbc.execute("SELECT 1 / 0");
            throw new AssertionError("PostgreSQL must abort the transaction");
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        worker(assembler, registry).generate(exportId, accountId);

        assertFailureCommitted(exportId, accountId, registry, "assembly_failed", 1);
    }

    @Test
    void shouldCommitFailureWithoutSuccessMetricsWhenGenerationCannotCommit() {
        Long accountId = Objects.requireNonNull(accountRepository
                .save(new Account("Export commit failure subject"))
                .getId());
        Long exportId = seedExport(accountId);
        ExportBundleAssembler assembler = mock(ExportBundleAssembler.class);
        when(assembler.assemble(accountId)).thenAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    jdbc.update("UPDATE account_export SET failure_reason = ? WHERE id = ?", "x".repeat(129), exportId);
                }
            });
            return bundle(accountId);
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        worker(assembler, registry).generate(exportId, accountId);

        assertFailureCommitted(exportId, accountId, registry, "assembly_failed", 1);
    }

    private ExportGenerationWorker worker(ExportBundleAssembler assembler, SimpleMeterRegistry registry) {
        return new ExportGenerationWorker(
                repository,
                accountRepository,
                assembler,
                conversations,
                feedback,
                ignored -> null,
                objectMapper,
                new AccountExportWriter(repository, accountRepository, clock),
                new PrivacyJobMetrics(registry),
                transactionManager,
                ownerScope);
    }

    private void setAccountStatus(long accountId, Account.Status status) {
        jdbc.update(
                "UPDATE account SET status = ?, deleted_at = CURRENT_TIMESTAMP WHERE id = ?", status.name(), accountId);
    }

    private ExportBundle bundle(long accountId) {
        var profile = new ExportBundle.Profile(
                Long.toString(accountId), "User", null, null, "ACTIVE", clock.instant(), false);
        return new ExportBundle(
                ExportBundle.SCHEMA_VERSION,
                clock.instant(),
                profile,
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    private long seedExport(long accountId) {
        return Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> {
            ownerScope.ofJob(accountId);
            return repository.save(new AccountExport(accountId)).getId();
        }));
    }

    private AccountExport readExport(long exportId, long accountId) {
        return Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> {
            ownerScope.ofJob(accountId);
            return repository.findById(exportId).orElseThrow();
        }));
    }

    private void assertFailureCommitted(
            long exportId, long accountId, SimpleMeterRegistry registry, String reason, int failures) {
        AccountExport export = readExport(exportId, accountId);
        assertThat(export.getStatus()).isEqualTo(AccountExport.Status.FAILED);
        assertThat(export.getFailureReason()).isEqualTo(reason);
        assertThat(export.getPayload()).isNull();
        assertThat(export.getCompletedAt()).isNull();
        assertThat(export.getExpiresAt()).isNull();
        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_generation", "outcome", "failure")
                        .counter()
                        .count())
                .isEqualTo(failures);
        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_generation", "outcome", "success")
                        .counter()
                        .count())
                .isZero();
        assertThat(registry.find("privacy.job.affected").counter()).isNull();
    }
}
