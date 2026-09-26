package de.tum.cit.aet.hephaestus.core.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeatureRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the instance-admin authority string emitted into the JWT. It MUST be the namespaced
 * {@code app_admin} (matching the {@code /.well-known} discovery doc and the {@code @PreAuthorize}
 * checks), NOT the legacy bare {@code admin} (which collides with the per-workspace "admin" role).
 * This is the drift guard against the issuer, the discovery doc, and the authorize rules diverging.
 */
class JwtPrincipalFactoryRolesTest extends BaseUnitTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final IdentityLinkRepository identityLinkRepository = mock(IdentityLinkRepository.class);
    private final AccountFeatureRepository featureRepository = mock(AccountFeatureRepository.class);
    private final JwtPrincipalFactory factory =
            new JwtPrincipalFactory(accountRepository, identityLinkRepository, featureRepository);

    private Account active(long id, Account.AppRole role) {
        when(featureRepository.findFlagsByAccountId(any())).thenReturn(List.of());
        when(identityLinkRepository.findActiveByAccountId(any())).thenReturn(List.of());
        Account account = new Account("Test " + id);
        account.setId(id);
        account.setAppRole(role);
        return account;
    }

    @Test
    void appAdminGetsNamespacedAppAdminAuthorityNotLegacyAdmin() {
        assertThat(factory.forAccount(active(1L, Account.AppRole.APP_ADMIN)).roles())
                .contains("app_admin")
                .doesNotContain("admin");
    }

    @Test
    void regularUserHasNeitherAdminAuthority() {
        assertThat(factory.forAccount(active(2L, Account.AppRole.USER)).roles()).doesNotContain("app_admin", "admin");
    }

    @Test
    void loginFallbackIsANonCollidableSentinelNotTheUserControlledDisplayName() {
        // No usable identity-link username → the login must NOT be the OAuth display name (user-controlled
        // free text that could equal another user's git login). A ':' can't appear in a git login, so the
        // sentinel matches no real User.login.
        Account account = active(7L, Account.AppRole.USER);
        account.setDisplayName("octocat"); // an existing git login, were it ever used as preferred_username

        assertThat(factory.forAccount(account).login()).isEqualTo("account:7").isNotEqualTo("octocat");
    }

    @Test
    void grantedReservedFeatureFlagDoesNotEscalateToInstanceAdmin() {
        // account_feature.flag is free-text; a /admin/users-granted row must NOT be able to inject the
        // instance-admin authority. Privilege separation: app_admin comes only from appRole==APP_ADMIN.
        when(featureRepository.findFlagsByAccountId(any()))
                .thenReturn(List.of("app_admin", "admin", "notification_access"));
        when(identityLinkRepository.findActiveByAccountId(any())).thenReturn(List.of());
        Account user = new Account("Sneaky");
        user.setId(3L);
        user.setAppRole(Account.AppRole.USER);

        assertThat(factory.forAccount(user).roles())
                .contains("notification_access")
                .doesNotContain("app_admin", "admin");
    }
}
