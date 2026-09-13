package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Verifies the atomic, self-disabling first-admin promotion against PostgreSQL. */
class AccountBootstrapServiceIntegrationTest extends BaseIntegrationTest {

    static final String TOKEN = "bootstrap-break-glass-token-please-rotate";

    private AccountBootstrapService bootstrapService;

    @Autowired
    private AuthEventLogger authEventLogger;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        AuthProperties properties = mock(AuthProperties.class);
        when(properties.bootstrapToken()).thenReturn(TOKEN);
        bootstrapService = new AccountBootstrapService(
                accountRepository,
                authEventLogger,
                properties,
                java.time.Clock.systemUTC(),
                org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    private Account persistUser(String name) {
        return accountRepository.save(new Account(name));
    }

    private Account persistAdmin(String name) {
        Account a = new Account(name);
        a.setAppRole(Account.AppRole.APP_ADMIN);
        return accountRepository.save(a);
    }

    @Test
    void promotesCallerWhenNoAdminExists() {
        Account user = persistUser("Hopeful");

        inTransaction(() -> bootstrapService.bootstrapFirstAdmin(persistedId(user.getId()), TOKEN));

        assertThat(accountRepository
                        .findById(persistedId(user.getId()))
                        .orElseThrow()
                        .getAppRole())
                .isEqualTo(Account.AppRole.APP_ADMIN);
    }

    @Test
    void selfDisablesOnceAnAdminExists() {
        persistAdmin("Existing Admin");
        Account user = persistUser("Late Hopeful");

        assertThatThrownBy(() ->
                        inTransaction(() -> bootstrapService.bootstrapFirstAdmin(persistedId(user.getId()), TOKEN)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(accountRepository
                        .findById(persistedId(user.getId()))
                        .orElseThrow()
                        .getAppRole())
                .isEqualTo(Account.AppRole.USER);
    }

    private static long persistedId(@Nullable Long id) {
        assertNotNull(id);
        return id;
    }

    private void inTransaction(Runnable action) {
        transactionTemplate.executeWithoutResult(status -> action.run());
    }
}
