package de.tum.cit.aet.hephaestus.core.auth.export;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.export.dto.ExportStatusDTO;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonAct;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * GDPR Art. 20 self-service data export. Owns the {@link AccountExport} lifecycle and the
 * ownership checks behind {@code /user/exports/**}; the controller stays a thin HTTP adapter
 * (no repository access — enforced by {@code ArchitectureTest.controllersDoNotAccessRepositories}).
 *
 * <h2>Ownership / enumeration defense</h2>
 * Every read is scoped by {@code (id, accountId)}. A caller asking for another account's export
 * id gets {@link Optional#empty()}, which the controller maps to 404 (never 403) so an attacker
 * cannot distinguish "exists but not yours" from "doesn't exist".
 *
 * <h2>Async generation</h2>
 * {@link #requestExport} persists a PENDING row and publishes {@link AccountExportRequested} in the
 * request transaction, so the event publication registry records the generation that is owed. The
 * request returns 202 immediately; the client polls {@link #status}. {@link ExportGenerationWorker}
 * generates after commit, and a restart before it finishes leaves an incomplete publication that is
 * resubmitted.
 */
@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("GDPR exports are account-scoped, spanning a principal's data across workspaces")
public class AccountExportService {

    private final AccountExportRepository accountExportRepository;
    private final AccountRepository accounts;
    private final ApplicationEventPublisher events;
    private final AuthEventLogger authEventLogger;
    private final Clock clock;
    private final OwnerScope ownerScope;

    public AccountExportService(
            AccountExportRepository accountExportRepository,
            AccountRepository accounts,
            ApplicationEventPublisher events,
            AuthEventLogger authEventLogger,
            Clock clock,
            OwnerScope ownerScope) {
        this.accountExportRepository = accountExportRepository;
        this.accounts = accounts;
        this.events = events;
        this.authEventLogger = authEventLogger;
        this.clock = clock;
        this.ownerScope = ownerScope;
    }

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

    /**
     * Create a PENDING export for the account and kick off async generation.
     *
     * @throws ResponseStatusException 409 CONFLICT if the account is being erased or already has an
     *     export in progress.
     */
    @Transactional
    public AccountExport requestExport(PersonAct act) {
        long accountId = ownerScope.fromPrincipal(act);
        // Hold the account lock through insertion and durable event publication. Erasure locks it before deleting
        // exports.
        if (accounts.lockStatusForShare(accountId)
                .map(Account.Status::valueOf)
                .filter(AccountExport::accountAllowsExport)
                .isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This account is unavailable for export.");
        }
        // Fast-path friendly check; the authoritative guard is the partial unique index
        // uq_account_export_in_flight (changeset -14-), which closes the check-then-insert TOCTOU below.
        if (accountExportRepository.existsByAccountIdAndStatusIn(accountId, IN_FLIGHT)) {
            throw inFlightConflict();
        }
        AccountExport export;
        try {
            export = accountExportRepository.saveAndFlush(new AccountExport(accountId));
        } catch (DataIntegrityViolationException e) {
            // Lost the race: a concurrent POST inserted the in-flight row first and won the unique index.
            throw inFlightConflict(e);
        }
        authEventLogger
                .event(AuthEvent.EventType.EXPORT_REQUESTED, AuthEvent.Result.SUCCESS)
                .account(accountId)
                .details("{\"exportId\":" + export.getId() + "}")
                .recordInCurrentTransaction();
        events.publishEvent(new AccountExportRequested(Objects.requireNonNull(export.getId()), accountId));
        return export;
    }

    /** Ownership-scoped status view; empty if the id doesn't exist or isn't this account's. */
    @Transactional(readOnly = true)
    public Optional<ExportStatusDTO> status(Long exportId, PersonAct act) {
        long accountId = ownerScope.fromPrincipal(act);
        return accountExportRepository.findByIdAndAccountId(exportId, accountId).map(this::toStatus);
    }

    /**
     * Ownership-scoped payload fetch for download. Returns the JSON bytes only when the export is
     * READY and not past its expiry; empty otherwise (missing, not-yours, not-ready, or expired)
     * so the controller answers 404 uniformly.
     */
    @Transactional(readOnly = true)
    public Optional<byte[]> downloadPayload(Long exportId, PersonAct act) {
        long accountId = ownerScope.fromPrincipal(act);
        return accountExportRepository
                .findByIdAndAccountId(exportId, accountId)
                .filter(e -> e.getStatus() == AccountExport.Status.READY)
                .filter(e -> e.getExpiresAt() == null || e.getExpiresAt().isAfter(Instant.now(clock)))
                .flatMap(export -> Optional.<byte[]>ofNullable(export.getPayload()))
                .filter(payload -> payload.length > 0);
    }

    private ExportStatusDTO toStatus(AccountExport e) {
        return new ExportStatusDTO(
                Objects.requireNonNull(e.getId()),
                e.getStatus().name(),
                Objects.requireNonNull(e.getRequestedAt()),
                e.getCompletedAt(),
                e.getExpiresAt());
    }
}
