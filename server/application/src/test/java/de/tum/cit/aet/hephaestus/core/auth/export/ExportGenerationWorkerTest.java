package de.tum.cit.aet.hephaestus.core.auth.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
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
        worker = new ExportGenerationWorker(
                repository, assembler, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), metrics, transactionManager);
    }

    private AccountExport existingExport() {
        AccountExport export = new AccountExport(ACCOUNT_ID);
        when(repository.findByIdAndAccountId(EXPORT_ID, ACCOUNT_ID)).thenReturn(Optional.of(export));
        return export;
    }

    @Test
    void shouldSetPayloadExpiryAndReadyWhenGenerationSucceeds() {
        AccountExport export = existingExport();
        ExportBundle.Profile profile = new ExportBundle.Profile(ACCOUNT_ID, "User", null, "ACTIVE", NOW);
        ExportBundle bundle =
                new ExportBundle("v1", NOW, profile, List.of(), List.of(), List.of(), null, List.of(), null);
        when(assembler.assemble(ACCOUNT_ID)).thenReturn(bundle);
        when(objectMapper.writeValueAsBytes(bundle)).thenReturn(new byte[] {1, 2, 3});

        worker.generate(EXPORT_ID, ACCOUNT_ID);

        assertThat(export.getStatus()).isEqualTo(AccountExport.Status.READY);
        assertThat(export.getPayload()).containsExactly(1, 2, 3);
        assertThat(export.getCompletedAt()).isEqualTo(NOW);
        assertThat(export.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(48)));
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
    void shouldMarkFailedWhenTheInitialLookupFails() {
        AccountExport export = new AccountExport(ACCOUNT_ID);
        when(repository.findByIdAndAccountId(EXPORT_ID, ACCOUNT_ID))
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
    void shouldMarkFailedWhenSavingProcessingFails() {
        AccountExport export = existingExport();
        when(repository.save(export))
                .thenThrow(new IllegalStateException("save failed"))
                .thenReturn(export);

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
    void shouldReportFailureWhenTheDatabaseCannotRecordIt() {
        when(repository.findByIdAndAccountId(EXPORT_ID, ACCOUNT_ID)).thenThrow(new IllegalStateException("db down"));

        worker.generate(EXPORT_ID, ACCOUNT_ID);

        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_generation", "outcome", "failure")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.find("privacy.job.completed")
                        .tags("job", "export_generation", "outcome", "success")
                        .counter())
                .isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void shouldWriteNothingAndReportFailureWhenRowIsMissing() {
        when(repository.findByIdAndAccountId(EXPORT_ID, ACCOUNT_ID)).thenReturn(Optional.empty());

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
