package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.event.AccountDeletionScheduledEvent;
import de.tum.cit.aet.hephaestus.core.event.AccountSecurityChangedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Account read + lifecycle operations behind the {@code /user} and {@code /admin/users}
 * controllers. Owns the data-layer access so the controllers stay thin and never touch a
 * repository directly (enforced by {@code ArchitectureTest.controllersDoNotAccessRepositories}).
 */
@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("Account operations are user/system-scoped, not workspace-scoped")
public class AccountService {

    private final AccountRepository accountRepository;
    private final IdentityLinkRepository identityLinkRepository;
    private final IssuedJwtRepository issuedJwtRepository;
    private final AuthEventLogger authEventLogger;
    private final AuthProperties authProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public AccountService(
            AccountRepository accountRepository,
            IdentityLinkRepository identityLinkRepository,
            IssuedJwtRepository issuedJwtRepository,
            AuthEventLogger authEventLogger,
            AuthProperties authProperties,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.identityLinkRepository = identityLinkRepository;
        this.issuedJwtRepository = issuedJwtRepository;
        this.authEventLogger = authEventLogger;
        this.authProperties = authProperties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public Account requireById(Long id) {
        return accountRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
    }

    public List<IdentityLink> activeIdentities(Long accountId) {
        return identityLinkRepository.findActiveByAccountId(accountId);
    }

    /**
     * GDPR Art. 17 soft-delete: status → DELETING + deleted_at (start of the cooldown window), and
     * revoke all sessions immediately. Once {@code deleted_at} is older than
     * {@code hephaestus.auth.delete-cooldown} (default 48h), {@link AccountHardDeleteSweeper} purges
     * the account's personal/auth child rows (identity_link, account_feature, issued_jwt,
     * account_export) and flips the row to DELETED. Retained, lawful-basis audit data (auth_event,
     * Art. 30) and the read-only git-activity mirror (Art. 17(3)) are intentionally kept.
     *
     * @param actingAccountId the impersonating operator's id when this runs under an {@code act} claim,
     *     else {@code null} for a genuine self-service deletion. Stamped on the audit row so an
     *     operator-driven erasure records the (target, operator) pair instead of reading as the victim
     *     self-deleting — non-repudiation on the highest-risk action.
     */
    @Transactional
    public void softDelete(Long accountId, @Nullable Long actingAccountId) {
        Account account = requireById(accountId);
        if (account.getStatus() == Account.Status.DELETING || account.getStatus() == Account.Status.DELETED) {
            // Idempotent: only ACTIVE/SUSPENDED → DELETING starts the Art.17 cooldown. A re-invocation
            // must NOT reset deleted_at (restarting the 48h purge clock) or re-emit ACCOUNT_DELETED.
            return;
        }
        Instant deletedAt = clock.instant();
        account.setStatus(Account.Status.DELETING);
        account.setDeletedAt(deletedAt);
        accountRepository.save(account);
        issuedJwtRepository.revokeAllForAccount(accountId, deletedAt, IssuedJwt.RevokedReason.ACCOUNT_DELETED);
        authEventLogger
                .event(AuthEvent.EventType.ACCOUNT_DELETED, AuthEvent.Result.SUCCESS)
                .account(accountId)
                .actingAccount(actingAccountId)
                .record();
        // Same transaction as the status flip: the confirmation email is only owed once the
        // cooldown really started, and the registry row commits with it.
        eventPublisher.publishEvent(
                new AccountDeletionScheduledEvent(accountId, deletedAt.plus(authProperties.deleteCooldown())));
    }

    /**
     * Unlink (remove) a federated identity from the current account. Two safety rules, both
     * standard for account-linking UIs:
     * <ul>
     *   <li><b>Ownership</b> — only a link the account actually owns can be removed (404 otherwise),
     *       so one user can never detach another's identity.</li>
     *   <li><b>Last-identity lockout</b> — the account's only remaining sign-in method cannot be
     *       removed (409); to drop the last identity a user deletes the account instead.</li>
     * </ul>
     * Reversible: re-linking only requires signing in with that provider again. The current session
     * is account-scoped (not identity-scoped), so unlinking never logs the user out.
     *
     * @param actingAccountId the impersonating operator's id when this runs under an {@code act} claim,
     *     else {@code null} for a self-service unlink. Stamped on the audit row for operator attribution
     *     (see {@link #softDelete}).
     */
    @Transactional
    public void unlinkIdentity(Long accountId, Long identityLinkId, @Nullable Long actingAccountId) {
        // Write-lock the account's active links so two concurrent unlinks of different identities
        // serialize — otherwise both pass the last-identity guard below and drain the account to zero.
        List<IdentityLink> active = identityLinkRepository.findActiveByAccountIdForUpdate(accountId);
        IdentityLink target = active.stream()
                .filter(il -> il.getId().equals(identityLinkId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "identity link not found"));
        if (active.size() <= 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "You can't unlink your only sign-in method. Link another provider first, or delete your account.");
        }
        Long gitProviderId = target.getProviderId();
        if (identityLinkRepository.deleteByIdAndAccountId(identityLinkId, accountId) == 0) {
            // Lost a race (concurrently removed) — nothing to do; surface as not-found.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "identity link not found");
        }
        // The link row is now gone, so don't reference its id in the audit (its auth_event FK is
        // ON DELETE SET NULL anyway); account + provider record who unlinked which provider.
        authEventLogger
                .event(AuthEvent.EventType.IDENTITY_UNLINKED, AuthEvent.Result.SUCCESS)
                .account(accountId)
                .actingAccount(actingAccountId)
                .gitProvider(gitProviderId)
                .record();
        eventPublisher.publishEvent(new AccountSecurityChangedEvent(
                accountId, AccountSecurityChangedEvent.Kind.IDENTITY_UNLINKED, clock.instant()));
    }

    public List<Account> adminList(int page, int size) {
        int capped = Math.min(Math.max(size, 1), 200);
        return accountRepository
                .findAll(PageRequest.of(Math.max(page, 0), capped))
                .getContent();
    }

    @Transactional
    public Account adminSetRole(Long accountId, @Nullable String appRole, Long actingAccountId) {
        Account account = requireById(accountId);
        if (appRole != null) {
            Account.AppRole role;
            try {
                role = Account.AppRole.valueOf(appRole);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown app role: " + appRole, e);
            }
            // Last-admin lockout guard: demoting the only remaining APP_ADMIN — or yourself —
            // would lock everyone out of /admin with no recovery.
            boolean isDemotion = account.getAppRole() == Account.AppRole.APP_ADMIN && role != Account.AppRole.APP_ADMIN;
            if (isDemotion) {
                if (accountId.equals(actingAccountId)) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "You can't revoke your own admin access. Have another admin do it.");
                }
                // Locked (FOR UPDATE) count so concurrent demotions serialize instead of both passing.
                long activeAdmins = accountRepository
                        .findByAppRoleAndStatusForUpdate(Account.AppRole.APP_ADMIN, Account.Status.ACTIVE)
                        .size();
                if (activeAdmins <= 1) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "You can't revoke the last admin. Grant admin to another account first.");
                }
            }
            Account.AppRole previousRole = account.getAppRole();
            if (previousRole == role) {
                return account;
            }
            account.setAppRole(role);
            accountRepository.save(account);
            if (isDemotion) {
                // Privilege drop must take effect promptly: revoke the demoted admin's live sessions so a
                // stripped APP_ADMIN cannot retain admin authority until the access token's 15-min TTL
                // lapses. The revoked rows carry revoked_reason=ADMIN_REVOKE for forensics. Promotion is
                // deliberately NOT revoked — the new role is picked up on the next silent refresh with no
                // forced re-login.
                issuedJwtRepository.revokeAllForAccount(
                        accountId, clock.instant(), IssuedJwt.RevokedReason.ADMIN_REVOKE);
            }
            // Dedicated APP_ROLE_CHANGED type so the most security-sensitive mutation (granting/revoking
            // APP_ADMIN) is queryable and alertable on the indexed event_type column — the ADR's stated
            // mitigation for omitting step-up re-auth. Enum names are [A-Z_] only, so the inline JSON is
            // injection-safe. previousRole/role are both non-null (app_role is NOT NULL).
            authEventLogger
                    .event(AuthEvent.EventType.APP_ROLE_CHANGED, AuthEvent.Result.SUCCESS)
                    .account(accountId)
                    .actingAccount(actingAccountId)
                    .details("{\"from\":\"" + previousRole.name() + "\",\"to\":\"" + role.name() + "\"}")
                    .record();
            eventPublisher.publishEvent(new AccountSecurityChangedEvent(
                    accountId, AccountSecurityChangedEvent.Kind.APP_ROLE_CHANGED, clock.instant()));
        }
        return account;
    }

    /**
     * Instance-admin force sign-out: revoke all of {@code accountId}'s active sessions. Also ends any
     * in-flight impersonation OF this account — an impersonation token's subject is the target's id, so
     * its row is revoked here too; the operator's own session is a separate jti and is untouched.
     * Audited as {@code JWT_REVOKED}.
     */
    @Transactional
    public int adminRevokeAllSessions(Long accountId, Long actingAccountId) {
        requireById(accountId); // 404 if the account does not exist
        int revoked = issuedJwtRepository.revokeAllForAccount(
                accountId, clock.instant(), IssuedJwt.RevokedReason.ADMIN_REVOKE);
        authEventLogger
                .event(AuthEvent.EventType.JWT_REVOKED, AuthEvent.Result.SUCCESS)
                .account(accountId)
                .actingAccount(actingAccountId)
                .details("{\"reason\":\"ADMIN_REVOKE\",\"count\":" + revoked + "}")
                .record();
        return revoked;
    }
}
