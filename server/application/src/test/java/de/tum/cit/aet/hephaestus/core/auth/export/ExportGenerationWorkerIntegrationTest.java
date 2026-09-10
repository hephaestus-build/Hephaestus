package de.tum.cit.aet.hephaestus.core.auth.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class ExportGenerationWorkerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AccountExportRepository repository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldCommitFailureWhenTheAssemblyTransactionIsAborted() {
        Long accountId = Objects.requireNonNull(
                accountRepository.save(new Account("Failed export subject")).getId());
        Long exportId = Objects.requireNonNull(
                repository.save(new AccountExport(accountId)).getId());
        ExportBundleAssembler assembler = mock(ExportBundleAssembler.class);
        when(assembler.assemble(accountId)).thenAnswer(invocation -> {
            jdbc.execute("SELECT 1 / 0");
            throw new AssertionError("PostgreSQL must abort the transaction");
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExportGenerationWorker worker = new ExportGenerationWorker(
                repository, assembler, objectMapper, clock, new PrivacyJobMetrics(registry), transactionManager);

        worker.generate(exportId, accountId);

        assertFailureCommitted(exportId, registry);
    }

    @Test
    void shouldCommitFailureWithoutSuccessMetricsWhenGenerationCannotCommit() {
        Long accountId = Objects.requireNonNull(accountRepository
                .save(new Account("Export commit failure subject"))
                .getId());
        Long exportId = Objects.requireNonNull(
                repository.save(new AccountExport(accountId)).getId());
        ExportBundleAssembler assembler = mock(ExportBundleAssembler.class);
        when(assembler.assemble(accountId)).thenAnswer(invocation -> {
            // Dirty checking defers this constraint violation until the generation transaction commits.
            repository.findByIdAndAccountId(exportId, accountId).orElseThrow().setFailureReason("x".repeat(129));
            ExportBundle.Profile profile = new ExportBundle.Profile(accountId, "User", null, "ACTIVE", clock.instant());
            return new ExportBundle(
                    "v1",
                    clock.instant(),
                    profile,
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    List.of(),
                    java.util.List.of());
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExportGenerationWorker worker = new ExportGenerationWorker(
                repository, assembler, objectMapper, clock, new PrivacyJobMetrics(registry), transactionManager);

        worker.generate(exportId, accountId);

        assertFailureCommitted(exportId, registry);
    }

    private void assertFailureCommitted(Long exportId, SimpleMeterRegistry registry) {
        AccountExport export = repository.findById(exportId).orElseThrow();
        assertThat(export.getStatus()).isEqualTo(AccountExport.Status.FAILED);
        assertThat(export.getFailureReason()).isEqualTo("assembly_failed");
        assertThat(export.getPayload()).isNull();
        assertThat(export.getCompletedAt()).isNull();
        assertThat(export.getExpiresAt()).isNull();
        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_generation", "outcome", "failure")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.find("privacy.job.completed")
                        .tags("job", "export_generation", "outcome", "success")
                        .counter())
                .isNull();
        assertThat(registry.find("privacy.job.affected").counter()).isNull();
    }
}
