package de.tum.cit.aet.hephaestus.core.settings;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicUsagePolicy;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicUsageState;
import de.tum.cit.aet.hephaestus.core.settings.spi.ReleaseGate;
import de.tum.cit.aet.hephaestus.core.settings.spi.UsageFigure;
import de.tum.cit.aet.hephaestus.core.settings.spi.UsagePeriod;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The switch of the public usage page and its withdrawals (ADR 0153 decisions 13 to 16). Each change and its audit
 * event commit together, or neither does.
 */
@Service
@WorkspaceAgnostic("Instance publication policy applies to every workspace")
class PublicUsagePolicyService implements PublicUsagePolicy {
    private static final Logger log = LoggerFactory.getLogger(PublicUsagePolicyService.class);

    private final InstanceSettingsRepository settings;
    private final AccountRepository accounts;
    private final Optional<AuthEventLogger> audit;
    private final boolean configuredDefault;

    private volatile boolean lastKnownAllowed;

    PublicUsagePolicyService(
            InstanceSettingsRepository settings,
            AccountRepository accounts,
            Optional<AuthEventLogger> audit,
            @Value("${hephaestus.public-usage.enabled:false}") boolean configuredDefault) {
        this.settings = settings;
        this.accounts = accounts;
        this.audit = audit;
        this.configuredDefault = configuredDefault;
    }

    @Override
    @Transactional(readOnly = true)
    public PublicUsageState state() {
        return read(settings.findById(InstanceSettings.SINGLETON_ID));
    }

    @Override
    public boolean lastKnownAllowed() {
        return lastKnownAllowed;
    }

    /** Reads the switch for {@link #lastKnownAllowed()}; each replica keeps its own copy in memory. */
    @Scheduled(fixedDelayString = "PT30S")
    void refreshLastKnownAllowed() {
        try {
            lastKnownAllowed =
                    Optional.ofNullable(settings.readPublicUsageAllowed()).orElse(configuredDefault);
        } catch (DataAccessException | TransactionException unavailable) {
            log.debug("Could not read whether Hephaestus in numbers is on", unavailable);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReleaseGate lockForRelease() {
        settings.lockPublicUsage();
        // The row exists from here on, so the gate has a revision to compare at the end.
        settings.insertFailSafeSingletonIfMissing();
        var row = settings.readPublicUsageSwitch().orElseThrow();
        return new ReleaseGate(allowed(row), row.getVersion());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean unchangedUntilCommit(ReleaseGate gate) {
        var row = settings.readPublicUsageSwitchForShare().orElseThrow();
        return gate.allowed() && allowed(row) && row.getVersion() == gate.revision();
    }

    private boolean allowed(InstanceSettingsRepository.PublicUsageSwitch row) {
        return Optional.ofNullable(row.getPublicUsageAllowed()).orElse(configuredDefault);
    }

    @Transactional
    public PublicUsageState update(boolean allowed) {
        lockActingAdmin();
        var row = lockedRow();
        row.setPublicUsageAllowed(allowed);
        settings.saveAndFlush(row);
        record(
                AuthEvent.EventType.PUBLIC_USAGE_CHANGED,
                JsonMapper.shared().createObjectNode().put("allowed", allowed).toString());
        return read(Optional.of(row));
    }

    @Override
    @Transactional
    public PublicUsageState withdraw(UsagePeriod period, @Nullable UsageFigure figure) {
        lockActingAdmin();
        var row = lockedRow();
        var entry = PublicUsageState.entry(period, figure);
        if (!row.getPublicUsageWithdrawn().contains(entry)) {
            row.getPublicUsageWithdrawn().add(entry);
            settings.saveAndFlush(row);
            record(
                    AuthEvent.EventType.PUBLIC_USAGE_WITHDRAWN,
                    JsonMapper.shared()
                            .createObjectNode()
                            .put("period", period.key())
                            .put("figure", figure == null ? null : figure.name())
                            .toString());
        }
        return read(Optional.of(row));
    }

    private PublicUsageState read(Optional<InstanceSettings> row) {
        return row.map(found -> PublicUsageState.of(
                        Optional.ofNullable(found.getPublicUsageAllowed()).orElse(configuredDefault),
                        found.getPublicUsageWithdrawn()))
                .orElseGet(() -> PublicUsageState.of(configuredDefault, List.of()));
    }

    /**
     * Share-locks the acting admin's account before the settings row, the order in which an erasure takes them, and
     * refuses the change unless the account is still active. A request that began before its actor's erasure then
     * writes no audit event with the erased actor's address and agent.
     */
    private void lockActingAdmin() {
        boolean active = accounts.lockStatusForShare(CurrentAccount.requireId())
                .filter(Account.Status.ACTIVE.name()::equals)
                .isPresent();
        if (!active) {
            throw new AccessDeniedException("The acting account is no longer active");
        }
    }

    /**
     * The settings row under its own short write lock. A release share-locks the row only from its final check to its
     * commit, after its count and its inserts, so an admin waits at most for that commit.
     */
    private InstanceSettings lockedRow() {
        settings.insertFailSafeSingletonIfMissing();
        return settings.lockById(InstanceSettings.SINGLETON_ID).orElseThrow();
    }

    /** Stages the audit event in this transaction; a change that cannot be audited does not commit. */
    private void record(AuthEvent.EventType type, String details) {
        audit.ifPresent(logger -> {
            boolean staged = logger.event(type, AuthEvent.Result.SUCCESS)
                    .actingAccount(CurrentAccount.requireId())
                    .details(details)
                    .recordInCurrentTransaction();
            if (!staged) {
                throw new IllegalStateException("The audit event of a public usage change could not be recorded");
            }
        });
    }
}
