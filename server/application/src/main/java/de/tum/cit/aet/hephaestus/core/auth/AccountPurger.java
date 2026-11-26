package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import java.util.Map;
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
public class AccountPurger {

    private static final Logger log = LoggerFactory.getLogger(AccountPurger.class);

    // The erased tombstone still needs a non-null display name.
    private static final String TOMBSTONE_DISPLAY_NAME = "deleted-account";

    private final AccountRepository accountRepository;
    private final AccountErasureRepository erasureRepository;
    private final List<AccountErasureContributor> erasureContributors;
    private final ObjectProvider<PersonDataService> personErasure;
    private final PrivacyJournal journal;

    public AccountPurger(
            AccountRepository accountRepository,
            AccountErasureRepository erasureRepository,
            List<AccountErasureContributor> erasureContributors,
            ObjectProvider<PersonDataService> personErasure,
            PrivacyJournal journal) {
        this.accountRepository = accountRepository;
        this.erasureRepository = erasureRepository;
        this.erasureContributors = erasureContributors;
        this.personErasure = personErasure;
        this.journal = journal;
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
        // Lock the account before its child rows, in the same order as token issuance and export generation.
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
        erasureRepository.unlinkConsentDecisions(accountId);
        // Rows another module owns are erased by that module, inside this transaction.
        erasureContributors.forEach(contributor -> contributor.eraseAccount(accountId));
        erasureRepository.deleteIdentities(accountId);
        // Source writes precede export row locks, including the deferred expiry sweep.
        erasureRepository.deleteExports(accountId);
        // Both a self-deletion and an administrator's erasure of a person end here.
        journal.record(PrivacyJournalKind.ACCOUNT_ERASED, Map.of("accountId", accountId));

        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            return;
        }
        account.markDeleted();
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
