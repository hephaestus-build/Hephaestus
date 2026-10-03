package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Separate bean so each account purge crosses the transactional proxy and rolls back independently. */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("Account hard-delete is account-scoped; the sweep is global, not tenant data")
public class AccountPurger {

    private static final Logger log = LoggerFactory.getLogger(AccountPurger.class);

    /** PII-cleared placeholder left on the tombstone so the NOT NULL display_name column stays valid. */
    private static final String TOMBSTONE_DISPLAY_NAME = "deleted-account";

    private final AccountRepository accountRepository;
    private final AccountErasureRepository erasureRepository;
    private final List<AccountErasureContributor> erasureContributors;

    public AccountPurger(
            AccountRepository accountRepository,
            AccountErasureRepository erasureRepository,
            List<AccountErasureContributor> erasureContributors) {
        this.accountRepository = accountRepository;
        this.erasureRepository = erasureRepository;
        this.erasureContributors = erasureContributors;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void purge(Long accountId) {
        purgeRows(accountId);
    }

    /** The person-erasure step and its receipt must commit or roll back in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeInCurrentTransaction(Long accountId) {
        purgeRows(accountId);
    }

    private void purgeRows(Long accountId) {
        // The account row first, as every issuance and revocation takes it: a refresh holding its share
        // lock finishes before any child row goes, and none starts while the purge runs.
        accountRepository.lockStatusForUpdate(accountId);
        // Children carry ON DELETE CASCADE on account_id, but we keep the account tombstone, so the
        // cascade is not triggered — delete the personal/auth child rows explicitly.
        erasureRepository.deleteFeatures(accountId);
        anonymizeAuditRows(accountId); // reads identity_link, so before it is deleted
        erasureRepository.deleteIdentityLinks(accountId);
        erasureRepository.deleteSignInHandoffs(accountId);
        // Sessions before tokens, the order every session operation locks them in.
        erasureRepository.deleteClientSessions(accountId);
        erasureRepository.deleteIssuedTokens(accountId);
        erasureRepository.deleteExports(accountId);
        erasureRepository.unlinkConsentDecisions(accountId);
        // Rows another module owns are erased by that module, inside this transaction.
        erasureContributors.forEach(contributor -> contributor.eraseAccount(accountId));

        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            return;
        }
        account.setStatus(Account.Status.DELETED);
        account.setDisplayName(TOMBSTONE_DISPLAY_NAME);
        account.setPrimaryEmail(null);
        account.setPrimaryEmailVerifiedAt(null);
        accountRepository.save(account);
        log.info("auth.account: hard-deleted accountId={} (purged account-owned rows, status=DELETED)", accountId);
    }

    /**
     * {@code auth_event} and {@code config_audit_event} rows survive erasure: they are the security and
     * settings-change trail, and their non-identifying skeleton ({@code event_type}, {@code result},
     * {@code occurred_at}) is what the trail is. Only the personal columns are nulled.
     */
    private void anonymizeAuditRows(Long accountId) {
        int redacted = erasureRepository.redactAuthEvents(accountId);
        if (redacted > 0) {
            log.info("auth.account: anonymized {} auth_event row(s) for erased accountId={}", redacted, accountId);
        }

        int unlinked = erasureRepository.unlinkConfigAuditEvents(accountId);
        if (unlinked > 0) {
            log.info(
                    "auth.account: unlinked {} config_audit_event row(s) for erased accountId={}", unlinked, accountId);
        }
    }
}
