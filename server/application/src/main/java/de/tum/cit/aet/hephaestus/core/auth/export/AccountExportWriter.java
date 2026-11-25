package de.tum.cit.aet.hephaestus.core.auth.export;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Stores export requests and bundles under the account lock shared with erasure. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class AccountExportWriter {
    /** Retention window for a READY export before the sweep expires it and frees the payload. */
    static final Duration RETENTION = Duration.ofDays(2);

    private final AccountExportRepository accountExportRepository;
    private final AccountRepository accounts;
    private final Clock clock;

    /** Statuses that count as "in flight" for the one-export-per-account cap. */
    private static final List<AccountExport.Status> IN_FLIGHT =
            List.of(AccountExport.Status.PENDING, AccountExport.Status.PROCESSING);

    private static ResponseStatusException inFlightConflict() {
        return inFlightConflict(null);
    }

    private static ResponseStatusException inFlightConflict(@Nullable Throwable cause) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT, "An export is already in progress for this account.", cause);
    }

    public AccountExport createPending(long accountId) {
        // Keep the account locked through the caller's durable publication.
        if (!admit(accountId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This account is unavailable for export.");
        }
        // The partial unique index uq_account_export_in_flight covers the race between this check and insert.
        if (accountExportRepository.existsByAccountIdAndStatusIn(accountId, IN_FLIGHT)) {
            throw inFlightConflict();
        }
        try {
            return accountExportRepository.saveAndFlush(new AccountExport(accountId));
        } catch (DataIntegrityViolationException e) {
            // Lost the race: a concurrent POST inserted the in-flight row first and won the unique index.
            throw inFlightConflict(e);
        }
    }

    public enum Generation {
        READY,
        ACCOUNT_UNAVAILABLE,
        IN_PROGRESS,
        NOT_FOUND,
        ALREADY_DONE
    }

    public Generation finish(Long exportId, Long accountId, byte @Nullable [] payload, String failureReason) {
        // Take the account lifecycle lock before the export row, as the purge does. A null payload
        // can only clear an existing export, so recording failure does not require admission.
        boolean admitted = payload != null && admit(accountId);
        AccountExport export = accountExportRepository
                .lockByIdAndAccountId(exportId, accountId)
                .orElse(null);
        if (export == null) {
            return admitted ? Generation.NOT_FOUND : Generation.ALREADY_DONE;
        }
        if (!export.getStatus().inFlight()) {
            return Generation.ALREADY_DONE;
        }
        if (!admitted) {
            fail(export, failureReason);
            return Generation.ACCOUNT_UNAVAILABLE;
        }
        Instant now = clock.instant();
        export.setPayload(payload);
        export.setCompletedAt(now);
        export.setExpiresAt(now.plus(RETENTION));
        export.setStatus(AccountExport.Status.READY);
        accountExportRepository.save(export);
        return Generation.READY;
    }

    private boolean admit(long accountId) {
        return accounts.lockStatusForShare(accountId)
                .map(Account.Status::valueOf)
                .filter(AccountExport::accountAllowsExport)
                .isPresent();
    }

    private void fail(AccountExport export, String reason) {
        export.markFailed(reason);
        accountExportRepository.save(export);
    }
}
