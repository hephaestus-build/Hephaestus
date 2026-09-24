package de.tum.cit.aet.hephaestus.core.auth.jwt;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeatureRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Regression guard for the ADR 0017 account-status gate: every JWT-issue path (login success
 * handler, refresh) funnels through {@link JwtPrincipalFactory#forAccount} which is
 * the last line that must refuse a non-ACTIVE account. Without it, a SUSPENDED account could log in
 * and a DELETING account (post "delete my account") could resurrect itself by re-authenticating.
 * These tests fail if the status check is removed — the status check short-circuits before any
 * repository access, so mocked repos are never touched for a rejected account.
 */
class JwtPrincipalFactoryStatusGateTest extends BaseUnitTest {

    private final JwtPrincipalFactory factory = new JwtPrincipalFactory(
            mock(AccountRepository.class), mock(IdentityLinkRepository.class), mock(AccountFeatureRepository.class));

    @Test
    void rejectsSuspendedAccount() {
        assertRejected(Account.Status.SUSPENDED);
    }

    @Test
    void rejectsDeletingAccount() {
        assertRejected(Account.Status.DELETING);
    }

    @Test
    void rejectsDeletedAccount() {
        assertRejected(Account.Status.DELETED);
    }

    private void assertRejected(Account.Status status) {
        Account account = new Account("Test Account");
        account.setStatus(status);
        assertThatThrownBy(() -> factory.forAccount(account))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not active");
    }
}
