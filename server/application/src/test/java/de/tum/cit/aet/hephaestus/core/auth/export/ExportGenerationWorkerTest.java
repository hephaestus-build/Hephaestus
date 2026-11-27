package de.tum.cit.aet.hephaestus.core.auth.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountFeedbackExport;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

class ExportGenerationWorkerTest extends BaseUnitTest {

    private static final long EXPORT_ID = 5L;
    private static final long ACCOUNT_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-06-02T10:00:00Z");

    private AccountExportRepository repository;
    private AccountRepository accounts;
    private ExportBundleAssembler assembler;
    private ObjectMapper objectMapper;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PrivacyJobMetrics metrics = new PrivacyJobMetrics(registry);
    private ExportGenerationWorker worker;

    @BeforeEach
    void setUp() {
        repository = mock(AccountExportRepository.class);
        assembler = mock(ExportBundleAssembler.class);
        objectMapper = mock(ObjectMapper.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        accounts = mock(AccountRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        worker = new ExportGenerationWorker(
                repository,
                accounts,
                assembler,
                accountId -> List.of(),
                mock(AccountFeedbackExport.class),
                ignored -> null,
                objectMapper,
                new AccountExportWriter(repository, accounts, clock),
                metrics,
                transactionManager,
                mock(OwnerScope.class));
    }

    private AccountExport existingExport() {
        pendingExport();
        AccountExport export = new AccountExport(ACCOUNT_ID);
        when(repository.lockByIdAndAccountId(EXPORT_ID, ACCOUNT_ID)).thenReturn(Optional.of(export));
        return export;
    }

    private void pendingExport() {
        when(repository.tryLockGeneration(EXPORT_ID)).thenReturn(true);
        when(repository.findStatus(EXPORT_ID, ACCOUNT_ID)).thenReturn(Optional.of(AccountExport.Status.PENDING));
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(new Account("User")));
    }

    @Test
    void shouldSetPayloadExpiryAndReadyWhenGenerationSucceeds() {
        AccountExport export = existingExport();
        when(accounts.lockStatusForShare(ACCOUNT_ID)).thenReturn(Optional.of(Account.Status.ACTIVE.name()));
        ExportBundle.Profile profile =
                new ExportBundle.Profile(Long.toString(ACCOUNT_ID), "User", null, null, "ACTIVE", NOW, false);
        ExportBundle bundle = new ExportBundle(
                ExportBundle.SCHEMA_VERSION,
                NOW,
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
        when(assembler.assemble(ACCOUNT_ID)).thenReturn(bundle);
        when(objectMapper.writeValueAsBytes(bundle)).thenReturn(new byte[] {1, 2, 3});

        worker.generate(EXPORT_ID, ACCOUNT_ID);

        assertThat(export.getStatus()).isEqualTo(AccountExport.Status.READY);
        assertThat(export.getPayload()).containsExactly(1, 2, 3);
        assertThat(export.getCompletedAt()).isEqualTo(NOW);
        assertThat(export.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(2)));
        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_generation", "outcome", "success")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("privacy.job.affected")
                        .tag("job", "export_generation")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldMarkFailedAndClearPayloadWhenAssemblyFails() {
        AccountExport export = existingExport();
        when(assembler.assemble(ACCOUNT_ID)).thenThrow(new RuntimeException("db unavailable"));

        worker.generate(EXPORT_ID, ACCOUNT_ID);

        assertThat(export.getStatus()).isEqualTo(AccountExport.Status.FAILED);
        assertThat(export.getFailureReason()).isEqualTo("assembly_failed");
        assertThat(export.getPayload()).isNull();
        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_generation", "outcome", "failure")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldMarkFailedWhenThePublicationLookupFails() {
        pendingExport();
        var bundle = mock(ExportBundle.class);
        when(assembler.assemble(ACCOUNT_ID)).thenReturn(bundle);
        // The export row lookup fails before the writer builds the payload.
        when(accounts.lockStatusForShare(ACCOUNT_ID)).thenReturn(Optional.of(Account.Status.ACTIVE.name()));
        AccountExport export = new AccountExport(ACCOUNT_ID);
        when(repository.lockByIdAndAccountId(EXPORT_ID, ACCOUNT_ID))
                .thenThrow(new IllegalStateException("lookup failed"))
                .thenReturn(Optional.of(export));

        worker.generate(EXPORT_ID, ACCOUNT_ID);

        assertThat(export.getStatus()).isEqualTo(AccountExport.Status.FAILED);
        assertThat(export.getFailureReason()).isEqualTo("assembly_failed");
        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_generation", "outcome", "failure")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldMarkFailedWhenSavingThePayloadFails() {
        pendingExport();
        AccountExport export = new AccountExport(ACCOUNT_ID);
        AccountExport rolledBack = new AccountExport(ACCOUNT_ID);
        var bundle = mock(ExportBundle.class);
        when(accounts.lockStatusForShare(ACCOUNT_ID)).thenReturn(Optional.of(Account.Status.ACTIVE.name()));
        when(repository.lockByIdAndAccountId(EXPORT_ID, ACCOUNT_ID))
                .thenReturn(Optional.of(export))
                .thenReturn(Optional.of(rolledBack));
        when(assembler.assemble(ACCOUNT_ID)).thenReturn(bundle);
        when(bundle.withErasableSources(List.of(), List.of(), List.of(), null)).thenReturn(bundle);
        when(objectMapper.writeValueAsBytes(bundle)).thenReturn(new byte[] {1});
        when(repository.save(export)).thenThrow(new IllegalStateException("save failed"));

        worker.generate(EXPORT_ID, ACCOUNT_ID);

        assertThat(rolledBack.getStatus()).isEqualTo(AccountExport.Status.FAILED);
        assertThat(rolledBack.getFailureReason()).isEqualTo("assembly_failed");
        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_generation", "outcome", "failure")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldReportFailureAndRethrowWhenTheDatabaseCannotRecordIt() {
        when(repository.tryLockGeneration(EXPORT_ID)).thenReturn(true);
        when(repository.findStatus(EXPORT_ID, ACCOUNT_ID)).thenThrow(new IllegalStateException("db down"));
        when(repository.lockByIdAndAccountId(EXPORT_ID, ACCOUNT_ID)).thenThrow(new IllegalStateException("db down"));

        // Rethrowing leaves the publication incomplete, so the registry resubmits the generation.
        assertThatThrownBy(() -> worker.generate(EXPORT_ID, ACCOUNT_ID)).hasMessage("db down");

        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_generation", "outcome", "failure")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_generation", "outcome", "success")
                        .counter()
                        .count())
                .isZero();
        verify(repository, never()).save(any());
    }

    @Test
    void shouldWriteNothingAndReportFailureWhenRowIsMissing() {
        when(repository.tryLockGeneration(EXPORT_ID)).thenReturn(true);
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(new Account("User")));

        worker.generate(EXPORT_ID, ACCOUNT_ID);

        verify(repository, never()).save(any());
        verify(assembler, never()).assemble(eq(ACCOUNT_ID));
        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_generation", "outcome", "failure")
                        .counter()
                        .count())
                .isEqualTo(1);
    }
}
