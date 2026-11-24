package de.tum.cit.aet.hephaestus.core.auth.export;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Job;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Outcome;
import de.tum.cit.aet.hephaestus.core.TransientFailures;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Generates the export that {@link AccountExportRequested} owes. The event publication registry
 * delivers it at least once: a generation that a stopped process left open runs again, and a delivery
 * for a terminal export does nothing. A transient database failure leaves the publication open. A generation
 * that fails for another reason records FAILED and completes its publication.
 */
@ConditionalOnServerRole
@Component
@OwnerScope.StoredOwnerOperation
public class ExportGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(ExportGenerationWorker.class);

    /** Retention window for a READY export before the sweep expires it and frees the payload. */
    static final Duration RETENTION = Duration.ofDays(2);

    private final AccountExportRepository accountExportRepository;
    private final AccountRepository accounts;
    private final ExportBundleAssembler assembler;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PrivacyJobMetrics metrics;
    private final TransactionTemplate transaction;
    private final OwnerScope ownerScope;

    public ExportGenerationWorker(
            AccountExportRepository accountExportRepository,
            AccountRepository accounts,
            ExportBundleAssembler assembler,
            ObjectMapper objectMapper,
            Clock clock,
            PrivacyJobMetrics metrics,
            PlatformTransactionManager transactionManager,
            OwnerScope ownerScope) {
        this.accountExportRepository = accountExportRepository;
        this.accounts = accounts;
        this.assembler = assembler;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.ownerScope = ownerScope;
    }

    // The generation owns its transactions: one for the bundle, and one for a failure after that
    // rolls back.
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(AccountExportRequested requested) {
        generate(requested.exportId(), requested.accountId());
    }

    /** Generate the export atomically; record a failure only after the failed transaction rolls back. */
    public void generate(Long exportId, Long accountId) {
        Generation generated;
        try {
            generated = Objects.requireNonNull(transaction.execute(status -> assemble(exportId, accountId)));
        } catch (JacksonException e) {
            recordFailure(exportId, accountId, "serialization_failed", e);
            return;
        } catch (RuntimeException e) {
            if (TransientFailures.isTransient(e)) {
                // A lock timeout can hide an uncommitted erasure. Redelivery checks its committed outcome.
                throw e;
            }
            recordFailure(exportId, accountId, "assembly_failed", e);
            return;
        }
        if (generated == Generation.IN_PROGRESS) {
            // Completing here could erase the owed publication if the other delivery has not committed
            // yet.
            throw new IllegalStateException("Export generation is already in progress.");
        }
        if (generated == Generation.ACCOUNT_UNAVAILABLE) {
            log.info("auth.export: generation withheld for export {}, reason=account_unavailable", exportId);
            return;
        }
        if (generated == Generation.ALREADY_DONE) {
            return;
        }
        metrics.record(Job.EXPORT_GENERATION, generated == Generation.READY ? Outcome.SUCCESS : Outcome.FAILURE);
        if (generated == Generation.READY) {
            metrics.recordAffected(Job.EXPORT_GENERATION, 1);
            log.info("auth.export: export {} for account {} READY", exportId, accountId);
        }
    }

    private enum Generation {
        READY,
        ACCOUNT_UNAVAILABLE,
        IN_PROGRESS,
        NOT_FOUND,
        ALREADY_DONE
    }

    private Generation assemble(Long exportId, Long accountId) {
        ownerScope.ofJob(accountId);
        if (!accountExportRepository.tryLockGeneration(exportId)) {
            return Generation.IN_PROGRESS;
        }
        var status = accountExportRepository.findStatus(exportId, accountId);
        if (status.isPresent() && !inFlight(status.orElseThrow())) {
            return Generation.ALREADY_DONE;
        }
        boolean eligible = accounts.findById(accountId)
                .map(Account::getStatus)
                .filter(AccountExport::accountAllowsExport)
                .isPresent();
        if (status.isEmpty()) {
            return missingExport(exportId, accountId, eligible);
        }
        if (!eligible) {
            // Account erasure is irreversible. A missing account also cannot own a retained export.
            return finish(exportId, accountId, null);
        }
        byte[] payload = objectMapper.writeValueAsBytes(assembler.assemble(accountId));
        return publish(exportId, accountId, payload);
    }

    private Generation publish(Long exportId, Long accountId, byte[] payload) {
        // Take the lifecycle lock only for publication, and before the export row, as the purge does.
        boolean eligible = accounts.lockStatusForShare(accountId)
                .map(Account.Status::valueOf)
                .filter(AccountExport::accountAllowsExport)
                .isPresent();
        return finish(exportId, accountId, eligible ? payload : null);
    }

    private Generation finish(Long exportId, Long accountId, byte @Nullable [] payload) {
        AccountExport export = accountExportRepository
                .lockByIdAndAccountId(exportId, accountId)
                .orElse(null);
        if (export == null) {
            return missingExport(exportId, accountId, payload != null);
        }
        if (!inFlight(export.getStatus())) {
            return Generation.ALREADY_DONE;
        }
        if (payload == null) {
            fail(export, "account_unavailable");
            return Generation.ACCOUNT_UNAVAILABLE;
        }
        Instant now = Instant.now(clock);
        export.setPayload(payload);
        export.setCompletedAt(now);
        export.setExpiresAt(now.plus(RETENTION));
        export.setStatus(AccountExport.Status.READY);
        accountExportRepository.save(export);
        return Generation.READY;
    }

    private Generation missingExport(long exportId, long accountId, boolean eligible) {
        if (!eligible) {
            return Generation.ALREADY_DONE;
        }
        log.warn("auth.export: generation skipped, export {} for account {} not found", exportId, accountId);
        return Generation.NOT_FOUND;
    }

    private static boolean inFlight(AccountExport.Status status) {
        return status == AccountExport.Status.PENDING || status == AccountExport.Status.PROCESSING;
    }

    private void recordFailure(Long exportId, Long accountId, String reason, RuntimeException failure) {
        log.error("auth.export: generation failed for export {} account {}", exportId, accountId, failure);
        metrics.record(Job.EXPORT_GENERATION, Outcome.FAILURE);
        try {
            // Repository errors can mark a transaction rollback-only; recovery needs a fresh transaction.
            transaction.executeWithoutResult(status -> {
                ownerScope.ofJob(accountId);
                accountExportRepository
                        .lockByIdAndAccountId(exportId, accountId)
                        .filter(export -> inFlight(export.getStatus()))
                        .ifPresent(export -> fail(export, reason));
            });
        } catch (RuntimeException recoveryFailure) {
            log.error(
                    "auth.export: could not record failure for export {} account {}",
                    exportId,
                    accountId,
                    recoveryFailure);
            // The export is still in flight; an incomplete publication is resubmitted, a completed one is
            // not.
            throw recoveryFailure;
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
