package de.tum.cit.aet.hephaestus.core.auth.jwt;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeatureRepository;
import de.tum.cit.aet.hephaestus.testconfig.AccountFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class JwtPrincipalFactoryStatusGateTest extends BaseUnitTest {

    private final JwtPrincipalFactory factory = new JwtPrincipalFactory(mock(AccountFeatureRepository.class));

    @Test
    void rejectsSuspendedAccount() {
        assertRejected(Account.Status.BLOCKED);
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
        Account account = AccountFixtures.withStatus("Test Account", status);
        account.setId(1L);
        assertThatThrownBy(() -> principal(account))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not active");
    }

    private JwtPrincipal principal(Account account) {
        return factory.forAuthority(Objects.requireNonNull(account.getId()), IssuanceAuthorityFixture.of(account));
    }
}
