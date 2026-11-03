package de.tum.cit.aet.hephaestus.core.auth.export;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.export.dto.ExportStatusDTO;
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
    private final ApplicationEventPublisher events;
    private final AuthEventLogger authEventLogger;
    private final Clock clock;

    public AccountExportService(
            AccountExportRepository accountExportRepository,
            ApplicationEventPublisher events,
            AuthEventLogger authEventLogger,
            Clock clock) {
        this.accountExportRepository = accountExportRepository;
        this.events = events;
        this.authEventLogger = authEventLogger;
        this.clock = clock;
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
     * @throws org.springframework.web.server.ResponseStatusException 409 CONFLICT if the account
     *     already has a PENDING/PROCESSING export — caps concurrent async bundle assemblies at one
     *     per account (DoS / storage-amplification guard).
     */
    @Transactional
    public AccountExport requestExport(Long accountId) {
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
                .record();
        events.publishEvent(new AccountExportRequested(Objects.requireNonNull(export.getId()), accountId));
        return export;
    }

    /** Ownership-scoped status view; empty if the id doesn't exist or isn't this account's. */
    @Transactional(readOnly = true)
    public Optional<ExportStatusDTO> status(Long exportId, Long accountId) {
        return accountExportRepository.findByIdAndAccountId(exportId, accountId).map(this::toStatus);
    }

    /**
     * Ownership-scoped payload fetch for download. Returns the JSON bytes only when the export is
     * READY and not past its expiry; empty otherwise (missing, not-yours, not-ready, or expired)
     * so the controller answers 404 uniformly.
     */
    @Transactional(readOnly = true)
    public Optional<byte[]> downloadPayload(Long exportId, Long accountId) {
        return accountExportRepository
                .findByIdAndAccountId(exportId, accountId)
                .filter(e -> e.getStatus() == AccountExport.Status.READY)
                .filter(e -> e.getExpiresAt() == null || e.getExpiresAt().isAfter(Instant.now(clock)))
                .flatMap(export -> Optional.<byte[]>ofNullable(export.getPayload()))
                .filter(payload -> payload.length > 0);
    }

    /**
     * Hourly retention enforcement: flips every READY export past its {@code expiresAt} to EXPIRED and
     * nulls the payload so exported PII isn't retained beyond the download window. One bulk
     * {@code @Modifying} UPDATE — no BYTEA blobs are loaded into the persistence context.
     *
     * <p>{@code @Transactional} is load-bearing: the bulk {@code @Modifying(flushAutomatically = true)}
     * query needs an active transaction, and the scheduler ({@link ExportRetentionSweeper}) calls this
     * across a real proxy hop with none of its own.
     *
     * @return the number of exports expired by this run
     */
    @Transactional
    public int expireRetention() {
        return accountExportRepository.expireReadyBefore(Instant.now(clock));
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
