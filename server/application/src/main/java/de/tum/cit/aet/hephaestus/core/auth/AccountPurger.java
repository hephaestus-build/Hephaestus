package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbcTemplate;
    private final List<AccountErasureContributor> erasureContributors;

    public AccountPurger(
            AccountRepository accountRepository,
            JdbcTemplate jdbcTemplate,
            List<AccountErasureContributor> erasureContributors) {
        this.accountRepository = accountRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.erasureContributors = erasureContributors;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void purge(Long accountId) {
        // Children carry ON DELETE CASCADE on account_id, but we keep the account tombstone, so the
        // cascade is not triggered — delete the personal/auth child rows explicitly.
        jdbcTemplate.update("DELETE FROM account_feature WHERE account_id = ?", accountId);
        anonymizeAuditRows(accountId); // reads identity_link, so before it is deleted
        jdbcTemplate.update("DELETE FROM identity_link WHERE account_id = ?", accountId);
        jdbcTemplate.update("DELETE FROM issued_jwt WHERE account_id = ?", accountId);
        jdbcTemplate.update("DELETE FROM account_export WHERE account_id = ?", accountId);
        jdbcTemplate.update("UPDATE consent_decision SET account_id = NULL WHERE account_id = ?", accountId);
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
        int redacted = jdbcTemplate.update(
                "UPDATE auth_event SET ip_inet = NULL, user_agent = NULL, details = NULL "
                        + "WHERE account_id = ? OR acting_account_id = ? "
                        + "OR viewed_user_id IN (SELECT external_actor_id FROM identity_link WHERE account_id = ?)",
                accountId,
                accountId,
                accountId);
        if (redacted > 0) {
            log.info("auth.account: anonymized {} auth_event row(s) for erased accountId={}", redacted, accountId);
        }

        // The append-only audit trigger permits nulling account references for erasure.
        int unlinked = jdbcTemplate.update(
                "UPDATE config_audit_event SET actor_account_id = NULL, acting_account_id = NULL "
                        + "WHERE actor_account_id = ? OR acting_account_id = ?",
                accountId,
                accountId);
        if (unlinked > 0) {
            log.info(
                    "auth.account: unlinked {} config_audit_event row(s) for erased accountId={}", unlinked, accountId);
        }
    }
}
