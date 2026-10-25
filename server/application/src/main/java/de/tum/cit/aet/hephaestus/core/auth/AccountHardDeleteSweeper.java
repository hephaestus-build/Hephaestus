package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Job;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Outcome;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Completes account erasure after the configured delete cooldown through
 * {@link AccountPurger#purge(Long)} and the exact-person erasure coordinator.
 *
 * <p>Each owning module removes its dependent personal proof and feedback before memberships and
 * identities. Evidence copies require physical cleanup acknowledgement. Unresolved external copies
 * keep erasure pending. The final step retains a minimal account tombstone and redacts retained audit
 * references.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("Account hard-delete is account-scoped; the sweep is global, not tenant data")
public class AccountHardDeleteSweeper {

    private static final Logger log = LoggerFactory.getLogger(AccountHardDeleteSweeper.class);

    private static final int PAGE_SIZE = 100;

    private final AccountRepository accountRepository;
    private final AccountPurger accountPurger;
    private final AuthProperties properties;
    private final Clock clock;
    private final PrivacyJobMetrics metrics;

    public AccountHardDeleteSweeper(
            AccountRepository accountRepository,
            AccountPurger accountPurger,
            AuthProperties properties,
            Clock clock,
            PrivacyJobMetrics metrics) {
        this.accountRepository = accountRepository;
        this.accountPurger = accountPurger;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Scheduled(cron = "0 5 * * * *")
    @SchedulerLock(name = "account-hard-delete-sweep", lockAtMostFor = "PT50M", lockAtLeastFor = "PT30S")
    public void sweep() {
        try {
            SweepResult result = purgeEligibleAccounts();
            metrics.record(Job.ACCOUNT_ERASURE, result.failed() == 0 ? Outcome.SUCCESS : Outcome.FAILURE);
            metrics.recordAffected(Job.ACCOUNT_ERASURE, result.purged());
            if (result.purged() > 0) {
                log.info("auth.account: hard-deleted {} account(s) past the GDPR delete cooldown", result.purged());
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
        Instant cutoff = clock.instant().minus(properties.deleteCooldown());
        int purged = 0;
        int failed = 0;
        // Always fetch page 0: a successful purge flips the account to DELETED so it drops out of
        // the
        // result set, advancing the window naturally. We stop when a page is empty (backlog
        // drained) or
        // when a page made ZERO progress (every purge threw) — otherwise the same failing rows
        // would be
        // re-fetched forever. Failed accounts stay DELETING and are retried on the next sweep.
        while (true) {
            List<Long> page = accountRepository.findDeletingPastCooldown(cutoff, PageRequest.of(0, PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }
            int purgedThisPage = 0;
            for (Long id : page) {
                try {
                    accountPurger.purge(id);
                    purged++;
                    purgedThisPage++;
                } catch (RuntimeException e) {
                    failed++;
                    log.error(
                            "auth.account: hard-delete FAILED for accountId={} (skipped, will retry" + " next sweep)",
                            id,
                            e);
                }
            }
            if (purgedThisPage == 0) {
                break;
            }
        }
        return new SweepResult(purged, failed);
    }

    private record SweepResult(int purged, int failed) {}
}
