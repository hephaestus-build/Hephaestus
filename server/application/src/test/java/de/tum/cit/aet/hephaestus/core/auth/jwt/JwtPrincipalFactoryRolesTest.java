package de.tum.cit.aet.hephaestus.core.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeatureRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class JwtPrincipalFactoryRolesTest extends BaseUnitTest {

    private final AccountFeatureRepository featureRepository = mock(AccountFeatureRepository.class);
    private final JwtPrincipalFactory factory = new JwtPrincipalFactory(featureRepository);

    private Account active(long id, Account.AppRole role) {
        when(featureRepository.findFlagsByAccountId(any())).thenReturn(List.of());
        Account account = new Account("Test " + id);
        account.setId(id);
        account.setAppRole(role);
        return account;
    }

    @Test
    void appAdminGetsNamespacedAppAdminAuthorityNotLegacyAdmin() {
        assertThat(principal(active(1L, Account.AppRole.APP_ADMIN)).roles())
                .contains("app_admin")
                .doesNotContain("admin");
    }

    @Test
    void regularUserHasNeitherAdminAuthority() {
        assertThat(principal(active(2L, Account.AppRole.USER)).roles()).doesNotContain("app_admin", "admin");
    }

    @Test
    void grantedReservedFeatureFlagDoesNotEscalateToInstanceAdmin() {
        // account_feature.flag is free-text; a row written by a database edit must NOT be able to
        // inject the
        // instance-admin authority. Privilege separation: app_admin comes only from
        // appRole==APP_ADMIN.
        when(featureRepository.findFlagsByAccountId(any()))
                .thenReturn(List.of("app_admin", "admin", "notification_access"));
        Account user = new Account("Sneaky");
        user.setId(3L);
        user.setAppRole(Account.AppRole.USER);

        assertThat(principal(user).roles()).contains("notification_access").doesNotContain("app_admin", "admin");
    }

    private JwtPrincipal principal(Account account) {
        return factory.forAuthority(Objects.requireNonNull(account.getId()), IssuanceAuthorityFixture.of(account));
    }
}
