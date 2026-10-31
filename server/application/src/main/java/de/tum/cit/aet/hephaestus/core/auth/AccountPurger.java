package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataService;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate bean so each account purge crosses the transactional proxy and rolls back independently.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("Account hard-delete is account-scoped; the sweep is global, not tenant data")
public class AccountPurger {

    private static final Logger log = LoggerFactory.getLogger(AccountPurger.class);

    // The erased tombstone still needs a non-null display name.
    private static final String TOMBSTONE_DISPLAY_NAME = "deleted-account";

    private final AccountRepository accountRepository;
    private final AccountErasureRepository erasureRepository;
    private final List<AccountErasureContributor> erasureContributors;
    private final ObjectProvider<PersonDataService> personErasure;

    public AccountPurger(
            AccountRepository accountRepository,
            AccountErasureRepository erasureRepository,
            List<AccountErasureContributor> erasureContributors,
            ObjectProvider<PersonDataService> personErasure) {
        this.accountRepository = accountRepository;
        this.erasureRepository = erasureRepository;
        this.erasureContributors = erasureContributors;
        this.personErasure = personErasure;
    }

    public void purge(Long accountId) {
        personErasure.getObject().eraseForAccount(accountId);
    }

    /** The person-erasure step and its receipt must commit or roll back in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeInCurrentTransaction(Long accountId) {
        purgeRows(accountId);
    }

    private void purgeRows(Long accountId) {
        // The account row first, as every issuance and revocation takes it: a refresh holding its
        // share
        // lock finishes before any child row goes, and none starts while the purge runs.
        accountRepository.lockStatusForUpdate(accountId);
        // Children carry ON DELETE CASCADE on account_id, but we keep the account tombstone, so the
        // cascade is not triggered — delete the personal/auth child rows explicitly.
        erasureRepository.deleteFeatures(accountId);
        erasureRepository.deletePublicActivityHiddenByDefault(accountId);
        anonymizeAuditRows(accountId); // reads identity, so before it is deleted
        accountRepository.retainPublicActivityObjections(accountId);
        erasureRepository.deleteSignInHandoffs(accountId);
        // Sessions before tokens, the order every session operation locks them in.
        erasureRepository.deleteClientSessions(accountId);
        erasureRepository.deleteIssuedTokens(accountId);
        erasureRepository.deleteExports(accountId);
        erasureRepository.unlinkConsentDecisions(accountId);
        // Rows another module owns are erased by that module, inside this transaction.
        erasureContributors.forEach(contributor -> contributor.eraseAccount(accountId));
        erasureRepository.deleteIdentities(accountId);

        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            return;
        }
        account.setStatus(Account.Status.DELETED);
        account.setDisplayName(TOMBSTONE_DISPLAY_NAME);
        account.setPrimaryEmail(null);
        account.setPrimaryEmailVerifiedAt(null);
        account.setAiChoice(null);
        account.setAiChoiceAt(null);
        account.setFeedbackDeliveryEnabled(false);
        account.setPublicActivityVisible(false);
        account.setTimeZone(null);
        accountRepository.save(account);
        log.info("auth.account: hard-deleted accountId={} (purged account-owned rows," + " status=DELETED)", accountId);
    }

    // Erasure clears personal audit columns while retaining the security and configuration event history.
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
