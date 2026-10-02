package de.tum.cit.aet.hephaestus.core.auth.jwt;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeatureRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Builds the {@link JwtPrincipal} (login + roles + given name) for an account at token-issue
 * time. Centralises the "what goes into the JWT claims" logic so the issuer
 * stays a thin signer and every issue path (login and refresh) is consistent.
 *
 * <h2>Login resolution</h2>
 * The {@code preferred_username} must remain the git-provider login that the existing
 * authorization model keys on ({@code preferred_username → User.login → WorkspaceMembership}).
 * We take it from the account's most-recently-used active {@link IdentityLink}'s
 * {@code usernameAtSignup} — the same value the provider returns as the login.
 *
 * <h2>Role resolution</h2>
 * {@code app_admin} when the account is {@link Account.AppRole#APP_ADMIN}, plus every enabled
 * {@code account_feature} flag (e.g. {@code notification_access}). The
 * instance-admin authority is namespaced {@code app_admin} (matching the {@code /.well-known}
 * discovery doc and {@code SecurityUtils.isSuperAdmin}) — deliberately distinct from the
 * per-workspace "admin" role, which is membership-derived and never appears in this token.
 */
@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("JWT principal assembly is account-scoped")
public class JwtPrincipalFactory {

    /**
     * Authority strings that must never originate from a grantable {@code account_feature} flag — the
     * instance-admin authority comes only from {@link Account.AppRole#APP_ADMIN}. {@code admin} is
     * also reserved (it is the legacy pre-rename string and the per-workspace role name).
     */
    private static final Set<String> RESERVED_INSTANCE_AUTHORITIES = Set.of("app_admin", "admin");

    private final IdentityLinkRepository identityLinkRepository;
    private final AccountFeatureRepository accountFeatureRepository;

    public JwtPrincipalFactory(
            IdentityLinkRepository identityLinkRepository, AccountFeatureRepository accountFeatureRepository) {
        this.identityLinkRepository = identityLinkRepository;
        this.accountFeatureRepository = accountFeatureRepository;
    }

    /**
     * The principal for an account whose row the caller has share-locked, built from the fields that lock
     * read. {@code HephaestusJwtIssuer} is the only caller: it assembles the principal after taking the
     * lock, so a demotion or suspension that commits while an issuance waits is what the token carries.
     * Runs in the caller's transaction.
     */
    public JwtPrincipal forAuthority(Long accountId, AccountRepository.IssuanceAuthority authority) {
        return createPrincipal(
                accountId,
                Account.Status.valueOf(authority.getStatus()),
                Account.AppRole.valueOf(authority.getAppRole()),
                authority.getDisplayName());
    }

    private JwtPrincipal createPrincipal(
            Long accountId, Account.Status status, Account.AppRole appRole, String displayName) {
        // Defense-in-depth account-status gate (ADR 0017). Every JWT-issue path funnels through here.
        // A SUSPENDED / DELETING / DELETED account must never be minted a principal — even if a caller
        // forgot the upstream check. The OAuth success handler rejects earlier with a friendly redirect;
        // this is the last line.
        if (status != Account.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "account is not active");
        }
        String login = resolveLogin(accountId);
        Set<String> roles = new HashSet<>(accountFeatureRepository.findFlagsByAccountId(accountId));
        // Privilege separation: the instance-admin authority derives ONLY from Account.appRole, never
        // from a grantable account_feature row. Strip any feature flag that collides with a reserved
        // instance authority so a `/admin/users`-granted flag can't inject super-admin (account_feature
        // .flag is free-text, so this is the enforcement point).
        roles.removeAll(RESERVED_INSTANCE_AUTHORITIES);
        if (appRole == Account.AppRole.APP_ADMIN) {
            roles.add("app_admin");
        }
        return new JwtPrincipal(accountId, login, displayName, roles);
    }

    /**
     * The git-provider login for the account: the username on its most recently used active identity
     * link. {@code preferred_username} carries this and the whole workspace-authorization model keys on
     * it ({@code preferred_username → User.login → WorkspaceMembership}).
     *
     * <p>Fallback when no active link has a usable username is a synthetic, colon-bearing sentinel —
     * NOT {@code account.getDisplayName()}. The display name is user-controlled free text from the OAuth
     * profile; emitting it as the login could (if it happened to equal another user's git login) leak
     * that user's membership-derived access. A git login can never contain {@code ':'}, so this sentinel
     * matches no real {@code User.login} — it fails safe (no spurious access) without locking out an
     * account whose provider returned no username (account-level, {@code sub}-based resolution still works).
     */
    private String resolveLogin(Long accountId) {
        return identityLinkRepository.findActiveByAccountId(accountId).stream()
                .filter(il -> il.getUsernameAtSignup() != null
                        && !il.getUsernameAtSignup().isBlank())
                .max(JwtPrincipalFactory::byLastLogin)
                .map(IdentityLink::getUsernameAtSignup)
                .orElse("account:" + accountId);
    }

    private static int byLastLogin(IdentityLink a, IdentityLink b) {
        var la = a.getLastLoginAt();
        var lb = b.getLastLoginAt();
        if (la == null && lb == null) return 0;
        if (la == null) return -1;
        if (lb == null) return 1;
        return la.compareTo(lb);
    }
}
