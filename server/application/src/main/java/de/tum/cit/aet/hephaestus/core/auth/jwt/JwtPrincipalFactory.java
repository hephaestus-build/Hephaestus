package de.tum.cit.aet.hephaestus.core.auth.jwt;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeatureRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("JWT principal assembly is account-scoped")
public class JwtPrincipalFactory {

    // Grantable feature flags must not confer instance-admin or workspace-admin authority, or claim
    // an authentication factor.
    private static final Set<String> RESERVED_INSTANCE_AUTHORITIES =
            Set.of("app_admin", "admin", "FACTOR_WEBAUTHN", "FACTOR_AUTHORIZATION_CODE");

    private final AccountFeatureRepository accountFeatureRepository;

    public JwtPrincipalFactory(AccountFeatureRepository accountFeatureRepository) {
        this.accountFeatureRepository = accountFeatureRepository;
    }

    /**
     * The principal for an account whose row the caller has share-locked, built from the fields
     * that lock read. {@code HephaestusJwtIssuer} is the only caller: it assembles the principal
     * after taking the lock, so a demotion or suspension that commits while an issuance waits is
     * what the token carries. Runs in the caller's transaction.
     */
    public JwtPrincipal forAuthority(Long accountId, AccountRepository.IssuanceAuthority authority) {
        return createPrincipal(
                accountId,
                Account.Status.valueOf(authority.getStatus()),
                Account.AppRole.valueOf(authority.getAppRole()));
    }

    private JwtPrincipal createPrincipal(Long accountId, Account.Status status, Account.AppRole appRole) {
        // Defense-in-depth account-status gate (ADR 0017). Every JWT-issue path funnels through
        // here.
        // A SUSPENDED / DELETING / DELETED account must never be minted a principal — even if a
        // caller
        // forgot the upstream check. The OAuth success handler rejects earlier with a friendly
        // redirect;
        // this is the last line.
        if (status != Account.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The account is not active.");
        }
        Set<String> roles = new HashSet<>(accountFeatureRepository.findFlagsByAccountId(accountId));
        // Privilege separation: the instance-admin authority derives ONLY from Account.appRole,
        // never
        // from a grantable account_feature row. Strip any feature flag that collides with a
        // reserved
        // instance authority so a `/admin/users`-granted flag can't inject super-admin
        // (account_feature
        // .flag is free-text, so this is the enforcement point).
        roles.removeAll(RESERVED_INSTANCE_AUTHORITIES);
        if (appRole == Account.AppRole.APP_ADMIN) {
            roles.add("app_admin");
        }
        return new JwtPrincipal(accountId, roles);
    }
}
