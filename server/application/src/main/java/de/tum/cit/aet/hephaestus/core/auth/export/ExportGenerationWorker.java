package de.tum.cit.aet.hephaestus.core.auth.export;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Job;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Outcome;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Separate bean from {@code AccountExportService}: self-invocation would bypass the {@link Async}
 * proxy and run the assembly inline on the request thread.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("GDPR export generation operates on a single account's data; not workspace-scoped")
public class ExportGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(ExportGenerationWorker.class);

    /** Retention window for a READY export before the sweep expires it and frees the payload. */
    static final Duration RETENTION = Duration.ofHours(48);

    private final AccountExportRepository accountExportRepository;
    private final ExportBundleAssembler assembler;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PrivacyJobMetrics metrics;
    private final TransactionTemplate transaction;

    public ExportGenerationWorker(
            AccountExportRepository accountExportRepository,
            ExportBundleAssembler assembler,
            ObjectMapper objectMapper,
            Clock clock,
            PrivacyJobMetrics metrics,
            PlatformTransactionManager transactionManager) {
        this.accountExportRepository = accountExportRepository;
        this.assembler = assembler;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Generate the export atomically; record a failure only after the failed transaction rolls back. */
    @Async
    public void generate(Long exportId, Long accountId) {
        boolean generated;
        try {
            generated = Boolean.TRUE.equals(transaction.execute(status -> assemble(exportId, accountId)));
        } catch (JacksonException e) {
            recordFailure(exportId, accountId, "serialization_failed", e);
            return;
        } catch (RuntimeException e) {
            recordFailure(exportId, accountId, "assembly_failed", e);
            return;
        }
        metrics.record(Job.EXPORT_GENERATION, generated ? Outcome.SUCCESS : Outcome.FAILURE);
        if (generated) {
            metrics.recordAffected(Job.EXPORT_GENERATION, 1);
            log.info("auth.export: export {} for account {} READY", exportId, accountId);
        }
    }

    private boolean assemble(Long exportId, Long accountId) {
        AccountExport export = accountExportRepository
                .findByIdAndAccountId(exportId, accountId)
                .orElse(null);
        if (export == null) {
            log.warn("auth.export: generation skipped, export {} for account {} not found", exportId, accountId);
            return false;
        }
        export.setStatus(AccountExport.Status.PROCESSING);
        accountExportRepository.save(export);

        ExportBundle bundle = assembler.assemble(accountId);
        byte[] payload = objectMapper.writeValueAsBytes(bundle);
        Instant now = Instant.now(clock);
        export.setPayload(payload);
        export.setCompletedAt(now);
        export.setExpiresAt(now.plus(RETENTION));
        export.setStatus(AccountExport.Status.READY);
        accountExportRepository.save(export);
        return true;
    }

    private void recordFailure(Long exportId, Long accountId, String reason, RuntimeException failure) {
        log.error("auth.export: generation failed for export {} account {}", exportId, accountId, failure);
        metrics.record(Job.EXPORT_GENERATION, Outcome.FAILURE);
        try {
            // Repository errors can mark a transaction rollback-only; recovery needs a fresh transaction.
            transaction.executeWithoutResult(status -> {
                accountExportRepository
                        .findByIdAndAccountId(exportId, accountId)
                        .ifPresent(export -> fail(export, reason));
            });
        } catch (RuntimeException recoveryFailure) {
            log.error(
                    "auth.export: could not record failure for export {} account {}",
                    exportId,
                    accountId,
                    recoveryFailure);
        }
    }

    private void fail(AccountExport export, String reason) {
        export.setStatus(AccountExport.Status.FAILED);
        export.setFailureReason(reason);
        export.setPayload(null);
        export.setCompletedAt(null);
        export.setExpiresAt(null);
        accountExportRepository.save(export);
    }
}
