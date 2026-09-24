package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountRoleQuery;
import de.tum.cit.aet.hephaestus.practices.spi.UserRoleChecker;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * DB-backed {@link UserRoleChecker} that resolves roles from {@code account_feature}
 * (replaces the former Keycloak-backed role checker; ADR 0017).
 *
 * <p>Delegates to {@link AccountRoleQuery} (the {@code core.auth} SPI) so role membership is
 * resolved from {@code account_feature} without this adapter reaching into the auth module's
 * domain types. Always {@link #isHealthy() healthy} — it is a local DB lookup, no external
 * dependency to trip a breaker; per the SPI contract, failures fail-closed inside the query.
 */
@Component
@WorkspaceAgnostic("Role checks are user-scoped ((gitProviderId, subject) → account → account_feature)")
public class AccountFeatureRoleChecker implements UserRoleChecker {

    private final AccountRoleQuery accountRoleQuery;

    public AccountFeatureRoleChecker(AccountRoleQuery accountRoleQuery) {
        this.accountRoleQuery = accountRoleQuery;
    }

    @Override
    public boolean hasRole(long gitProviderId, @NonNull String providerUserId, @NonNull String roleName) {
        // Fail-closed per the SPI contract: AccountRoleQuery null-guards and never throws.
        return accountRoleQuery.hasFeatureFlag(gitProviderId, providerUserId, roleName);
    }

    @Override
    public boolean isHealthy() {
        return true; // local DB lookup; no external dependency to gate on
    }
}
