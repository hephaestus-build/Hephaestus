package de.tum.cit.aet.hephaestus.core.auth.export;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Job;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Outcome;
import de.tum.cit.aet.hephaestus.core.TransientFailures;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExportWriter.Generation;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountConversationExport;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountFeedbackExport;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPersonalGoalExport;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Objects;
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
 *
 * <p>Another person's erasure can remove what the export holds: an answer in the account's conversations that used
 * their data, or feedback to the account that was derived from it. So the worker reads the conversation messages and
 * the received feedback only at publication, under the owner's export fence, and the database expires the owner's ready
 * exports when such a source is removed or edited ({@code person_row_sweep('EXPORT_SOURCES_CHANGED', …)}). The change
 * shares the same fence, so either the worker reads after the change commits, or the change expires what the worker
 * published.
 */
@ConditionalOnServerRole
@Component
@OwnerScope.StoredOwnerOperation
public class ExportGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(ExportGenerationWorker.class);

    private final AccountExportRepository accountExportRepository;
    private final AccountRepository accounts;
    private final ExportBundleAssembler assembler;
    private final AccountConversationExport conversations;
    private final AccountFeedbackExport feedback;
    private final AccountPersonalGoalExport goals;
    private final ObjectMapper objectMapper;
    private final AccountExportWriter writer;
    private final PrivacyJobMetrics metrics;
    private final TransactionTemplate transaction;
    private final OwnerScope ownerScope;

    public ExportGenerationWorker(
            AccountExportRepository accountExportRepository,
            AccountRepository accounts,
            ExportBundleAssembler assembler,
            AccountConversationExport conversations,
            AccountFeedbackExport feedback,
            AccountPersonalGoalExport goals,
            ObjectMapper objectMapper,
            AccountExportWriter writer,
            PrivacyJobMetrics metrics,
            PlatformTransactionManager transactionManager,
            OwnerScope ownerScope) {
        this.accountExportRepository = accountExportRepository;
        this.accounts = accounts;
        this.assembler = assembler;
        this.conversations = conversations;
        this.feedback = feedback;
        this.goals = goals;
        this.objectMapper = objectMapper;
        this.writer = writer;
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
        if (generated == Generation.NOT_FOUND) {
            log.warn("auth.export: generation skipped, export {} for account {} not found", exportId, accountId);
        }
        metrics.record(Job.EXPORT_GENERATION, generated == Generation.READY ? Outcome.SUCCESS : Outcome.FAILURE);
        if (generated == Generation.READY) {
            metrics.recordAffected(Job.EXPORT_GENERATION, 1);
            log.info("auth.export: export {} for account {} READY", exportId, accountId);
        }
    }

    private Generation assemble(Long exportId, Long accountId) {
        ownerScope.ofJob(accountId);
        if (!accountExportRepository.tryLockGeneration(exportId)) {
            return Generation.IN_PROGRESS;
        }
        var status = accountExportRepository.findStatus(exportId, accountId);
        if (status.isPresent() && !status.orElseThrow().inFlight()) {
            return Generation.ALREADY_DONE;
        }
        boolean eligible = accounts.findById(accountId)
                .map(Account::getStatus)
                .filter(AccountExport::accountAllowsExport)
                .isPresent();
        if (status.isEmpty()) {
            return eligible ? Generation.NOT_FOUND : Generation.ALREADY_DONE;
        }
        if (!eligible) {
            // Account erasure is irreversible. A missing account also cannot own a retained export.
            return writer.finish(exportId, accountId, null, "account_unavailable");
        }
        ExportBundle bundle = assembler.assemble(accountId);
        // The writer runs this under the owner's export fence, after the account lock and before the export row.
        return writer.finish(
                exportId,
                accountId,
                () -> objectMapper.writeValueAsBytes(bundle.withErasableSources(
                        conversations.messages(accountId),
                        feedback.received(accountId),
                        feedback.responses(accountId),
                        goals.words(accountId))),
                "account_unavailable");
    }

    private void recordFailure(Long exportId, Long accountId, String reason, RuntimeException failure) {
        log.error("auth.export: generation failed for export {} account {}", exportId, accountId, failure);
        metrics.record(Job.EXPORT_GENERATION, Outcome.FAILURE);
        try {
            // Repository errors can mark a transaction rollback-only; recovery needs a fresh transaction.
            transaction.executeWithoutResult(status -> {
                ownerScope.ofJob(accountId);
                writer.finish(exportId, accountId, null, reason);
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
}
