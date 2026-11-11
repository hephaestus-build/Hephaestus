package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Job;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Outcome;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.event.AccountDeletionRequestedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Completes account erasure through {@link AccountPurger#purge(Long)} and the exact-person erasure coordinator: at
 * once when a person deletes their own account, and every hour for each account that is still {@code DELETING}.
 *
 * <p>Each owning module removes its dependent personal proof and feedback before memberships and
 * identities. Evidence copies require physical cleanup acknowledgement. Unresolved external copies
 * keep erasure pending. The final step retains a minimal account tombstone and redacts retained audit
 * references. A purge and the hourly sweep can meet on one account: the erasure locks the account and its request,
 * and skips a store that it already erased.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("Account hard-delete is account-scoped; the sweep is global, not tenant data")
public class AccountHardDeleteSweeper {

    private static final Logger log = LoggerFactory.getLogger(AccountHardDeleteSweeper.class);

    private static final int PAGE_SIZE = 100;

    private final AccountRepository accountRepository;
    private final AccountPurger accountPurger;
    private final PrivacyJobMetrics metrics;

    public AccountHardDeleteSweeper(
            AccountRepository accountRepository, AccountPurger accountPurger, PrivacyJobMetrics metrics) {
        this.accountRepository = accountRepository;
        this.accountPurger = accountPurger;
        this.metrics = metrics;
    }

    /**
     * Purges an account as soon as its deletion commits. A purge that does not finish leaves the account
     * {@code DELETING} for the hourly sweep, so this does not ask the event registry to retry it too. It records no
     * job run: the sweep's runs are the signal that the retry still works.
     */
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(AccountDeletionRequestedEvent event) {
        try {
            accountPurger.purge(event.accountId());
        } catch (RuntimeException e) {
            log.warn(
                    "auth.account: hard-delete incomplete for accountId={}; the account stays DELETING for the hourly"
                            + " sweep. Check the person-data request and its failure code",
                    event.accountId(),
                    e);
        }
    }

    @Scheduled(cron = "0 5 * * * *")
    @SchedulerLock(name = "account-hard-delete-sweep", lockAtMostFor = "PT50M", lockAtLeastFor = "PT30S")
    public void sweep() {
        try {
            SweepResult result = purgeEligibleAccounts();
            metrics.record(Job.ACCOUNT_ERASURE, result.failed() == 0 ? Outcome.SUCCESS : Outcome.FAILURE);
            metrics.recordAffected(Job.ACCOUNT_ERASURE, result.purged());
            if (result.purged() > 0) {
                log.info("auth.account: hard-deleted {} account(s) that an earlier purge left", result.purged());
            }
        } catch (RuntimeException e) {
            metrics.record(Job.ACCOUNT_ERASURE, Outcome.FAILURE);
            throw e;
        }
    }

    /** Each account is purged in its own transaction so one failure does not block the backlog. */
    public int sweepNow() {
        return purgeEligibleAccounts().purged();
    }

    private SweepResult purgeEligibleAccounts() {
        int purged = 0;
        int failed = 0;
        // Each sweep visits every DELETING account once, in id order. A failed account stays DELETING for the next
        // sweep and does not hide the accounts after it.
        long afterId = 0;
        while (true) {
            List<Long> page = accountRepository.findDeleting(afterId, PageRequest.of(0, PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }
            afterId = page.getLast();
            for (Long id : page) {
                try {
                    accountPurger.purge(id);
                    purged++;
                } catch (RuntimeException e) {
                    failed++;
                    log.error(
                            "auth.account: hard-delete incomplete for accountId={}; check the person-data request and its failure code",
                            id,
                            e);
                }
            }
        }
        return new SweepResult(purged, failed);
    }

    private record SweepResult(int purged, int failed) {}
}
