package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.event.AccountDeletionRequestedEvent;
import de.tum.cit.aet.hephaestus.core.event.AccountSecurityChangedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("Account operations are user/system-scoped, not workspace-scoped")
public class AccountService {

    private final AccountRepository accountRepository;
    private final IdentityRepository identityRepository;
    private final SessionRevocation sessionRevocation;
    private final AuthEventLogger authEventLogger;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final GitProviderRegistry providers;

    public AccountService(
            AccountRepository accountRepository,
            IdentityRepository identityRepository,
            SessionRevocation sessionRevocation,
            AuthEventLogger authEventLogger,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            GitProviderRegistry providers) {
        this.accountRepository = accountRepository;
        this.identityRepository = identityRepository;
        this.sessionRevocation = sessionRevocation;
        this.authEventLogger = authEventLogger;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.providers = providers;
    }

    public Account requireById(Long id) {
        return accountRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The account was not found."));
    }

    public List<Identity> activeIdentities(Long accountId) {
        return identityRepository.findActiveByAccountId(accountId);
    }

    /**
     * GDPR Art. 17 deletion of the person's own account: status {@code DELETING}, {@code deleted_at}, and every
     * session revoked, in one step. Once this commits, {@link AccountHardDeleteSweeper} purges the account at once,
     * and its hourly sweep retries a purge that did not finish. There is no undo window: the person confirmed with a
     * recent sign-in, and nothing remains to stop. Retained, lawful-basis audit data (auth_event, Art. 30) and the
     * read-only git-activity mirror (Art. 17(3)) are intentionally kept.
     */
    @Transactional
    public void softDelete(Long accountId) {
        // Lock before reading, so the status change and the revocation are one step to every
        // issuance.
        sessionRevocation.lockAccount(accountId);
        Account account = requireById(accountId);
        if (account.getStatus() == Account.Status.DELETING || account.getStatus() == Account.Status.DELETED) {
            // Idempotent: only ACTIVE/BLOCKED → DELETING records ACCOUNT_DELETED and asks for the purge once.
            return;
        }
        account.setStatus(Account.Status.DELETING);
        account.setDeletedAt(clock.instant());
        accountRepository.save(account);
        sessionRevocation.revokeAccount(accountId, IssuedJwt.RevokedReason.ACCOUNT_DELETED, null, null);
        authEventLogger
                .event(AuthEvent.EventType.ACCOUNT_DELETED, AuthEvent.Result.SUCCESS)
                .account(accountId)
                .record();
        // Same transaction as the status flip: the purge runs only once the deletion committed.
        eventPublisher.publishEvent(new AccountDeletionRequestedEvent(accountId));
    }

    /**
     * Unlink (remove) a federated identity from the current account. Two safety rules, both
     * standard for account-linking UIs:
     *
     * <ul>
     *   <li><b>Ownership</b> — only a link the account actually owns can be removed (404
     *       otherwise), so one user can never detach another's identity.
     *   <li><b>Last-identity lockout</b> — the account's only remaining sign-in method cannot be
     *       removed (409); to drop the last identity a user deletes the account instead.
     * </ul>
     *
     * Reversible: re-linking only requires signing in with that provider again. The current session
     * is account-scoped (not identity-scoped), so unlinking never logs the user out.
     */
    @Transactional
    public void unlinkIdentity(Long accountId, Long identityId) {
        // Write-lock the account's active links so two concurrent unlinks of different identities
        // serialize — otherwise both pass the last-identity guard below and drain the account to
        // zero.
        accountRepository.lockStatusForUpdate(accountId);
        List<Identity> active = identityRepository.findActiveByAccountIdForUpdate(accountId);
        Identity target = active.stream()
                .filter(il -> il.getId().equals(identityId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "We could not find that connected account. It may"
                                + " have been deleted. Reload the page to see"
                                + " what is current."));
        if (isSignInIdentity(target)
                && active.stream().filter(this::isSignInIdentity).count() <= 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "You cannot disconnect your only sign-in method. Connect another provider"
                            + " first, or delete your account.");
        }
        accountRepository.retainPublicActivityObjections(accountId);
        Long gitProviderId = target.getProviderId();
        target.setDisabledAt(clock.instant());
        identityRepository.save(target);
        authEventLogger
                .event(AuthEvent.EventType.IDENTITY_UNLINKED, AuthEvent.Result.SUCCESS)
                .account(accountId)
                .gitProvider(gitProviderId)
                .identity(identityId)
                .record();
        eventPublisher.publishEvent(new AccountSecurityChangedEvent(
                accountId, AccountSecurityChangedEvent.Kind.IDENTITY_UNLINKED, clock.instant()));
    }

    private boolean isSignInIdentity(Identity identity) {
        String type = providers.providerTypeName(identity.getProviderId());
        return type.equals("GITHUB") || type.equals("GITLAB");
    }

    public Page<Account> adminList(int page, int size) {
        int capped = Math.min(Math.max(size, 1), 200);
        return accountRepository.findAll(PageRequest.of(Math.max(page, 0), capped, Sort.by("id")));
    }

    @Transactional
    public Account adminSetRole(Long accountId, @Nullable String appRole, Long actingAccountId) {
        // No target lock up front: two demotions would each hold their target while waiting for the
        // other
        // in the admin-set lock below. The role UPDATE holds the row until commit, and every
        // issuance
        // re-reads the role once it gets the row, so none can mint the old role after this commits.
        Account account = requireById(accountId);
        if (appRole != null) {
            Account.AppRole role;
            try {
                role = Account.AppRole.valueOf(appRole);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT, "The app role is not known: " + appRole, e);
            }
            // Last-admin lockout guard: demoting the only remaining APP_ADMIN — or yourself —
            // would lock everyone out of /admin with no recovery.
            boolean isDemotion = account.getAppRole() == Account.AppRole.APP_ADMIN && role != Account.AppRole.APP_ADMIN;
            if (isDemotion) {
                if (accountId.equals(actingAccountId)) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "You cannot revoke your own admin access. Ask another admin to do it.");
                }
                // Locked (FOR UPDATE) count so concurrent demotions serialize instead of both
                // passing.
                List<Account> activeAdmins = accountRepository.findByAppRoleAndStatusForUpdate(
                        Account.AppRole.APP_ADMIN, Account.Status.ACTIVE);
                boolean targetIsActiveAdmin = activeAdmins.stream().anyMatch(admin -> accountId.equals(admin.getId()));
                if (targetIsActiveAdmin && activeAdmins.size() <= 1) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "You cannot revoke the last admin. Grant admin to another account" + " first.");
                }
            }
            Account.AppRole previousRole = account.getAppRole();
            if (previousRole == role) {
                return account;
            }
            account.setAppRole(role);
            accountRepository.save(account);
            if (isDemotion) {
                // Privilege drop must take effect promptly: revoke the demoted admin's live
                // sessions so a stripped APP_ADMIN cannot retain admin authority until the access
                // token expires. The revoked rows carry revoked_reason=ADMIN_REVOKE for forensics.
                // Promotion is
                // deliberately NOT revoked — the new role is picked up on the next silent refresh
                // with no
                // forced re-login.
                sessionRevocation.revokeAccount(accountId, IssuedJwt.RevokedReason.ADMIN_REVOKE, null, null);
            }
            // Dedicated APP_ROLE_CHANGED type so the most security-sensitive mutation (granting or
            // revoking APP_ADMIN) is queryable and alertable on the indexed event_type column. The
            // handler also requires a recent sign-in (ADR 0017), and a refusal lands on this same type.
            // Enum names are [A-Z_] only, so the inline JSON is injection-safe. previousRole/role are
            // both non-null (app_role is NOT NULL).
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
     * Blocks or unblocks an account. A blocked account loses every session at once, and sign-in, token
     * renewal and installed clients refuse it until an instance admin unblocks it, so a provider sign-in
     * cannot undo the block.
     */
    @Transactional
    public Account adminSetStatus(Long accountId, Account.Status status, Long actingAccountId) {
        if (status != Account.Status.ACTIVE && status != Account.Status.BLOCKED) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "An admin can only block or unblock an account.");
        }
        boolean block = status == Account.Status.BLOCKED;
        if (block && accountId.equals(actingAccountId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "You cannot block your own account. Ask another admin to do it.");
        }
        // The admin set first, then the target: the order a demotion uses, so the two never wait on
        // each other. The locked set keeps two admins from blocking each other at the same time.
        List<Account> activeAdmins = block
                ? accountRepository.findByAppRoleAndStatusForUpdate(Account.AppRole.APP_ADMIN, Account.Status.ACTIVE)
                : List.of();
        sessionRevocation.lockAccount(accountId);
        Account account = requireById(accountId);
        Account.Status previous = account.getStatus();
        if (previous == status) {
            return account;
        }
        if (previous != Account.Status.ACTIVE && previous != Account.Status.BLOCKED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Only an active or blocked account can be blocked or unblocked.");
        }
        if (block && account.getAppRole() == Account.AppRole.APP_ADMIN && activeAdmins.size() <= 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "You cannot block the last instance admin. Grant admin to another account first.");
        }
        account.setStatus(status);
        accountRepository.save(account);
        if (block) {
            sessionRevocation.revokeAccount(accountId, IssuedJwt.RevokedReason.ADMIN_REVOKE, null, null);
        }
        authEventLogger
                .event(AuthEvent.EventType.ACCOUNT_STATUS_CHANGED, AuthEvent.Result.SUCCESS)
                .account(accountId)
                .actingAccount(actingAccountId)
                .details("{\"from\":\"" + previous.name() + "\",\"to\":\"" + status.name() + "\"}")
                .record();
        if (!block) {
            // A blocked account gets no mail (contacts are read for active accounts only), so the notice
            // goes out when the account can sign in again.
            eventPublisher.publishEvent(new AccountSecurityChangedEvent(
                    accountId, AccountSecurityChangedEvent.Kind.ACCOUNT_UNBLOCKED, clock.instant()));
        }
        return account;
    }

    @Transactional
    public int adminRevokeAllSessions(Long accountId, Long actingAccountId) {
        requireById(accountId); // 404 if the account does not exist
        int revoked = sessionRevocation.revokeAccount(accountId, IssuedJwt.RevokedReason.ADMIN_REVOKE, null, null);
        authEventLogger
                .event(AuthEvent.EventType.JWT_REVOKED, AuthEvent.Result.SUCCESS)
                .account(accountId)
                .actingAccount(actingAccountId)
                .details("{\"reason\":\"ADMIN_REVOKE\",\"count\":" + revoked + "}")
                .record();
        return revoked;
    }
}
