package de.tum.cit.aet.hephaestus.core.auth.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.account.AccountPreferencesQueryAdapter;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationQuery;
import de.tum.cit.aet.hephaestus.core.privacy.PrivacyJournalTestConfiguration;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AccountPreferencesQueryAdapterTest extends BaseUnitTest {
    @ParameterizedTest
    @EnumSource(RuntimeRole.class)
    void shouldStartEachRoleWithAccountChoicesOnlyOnTheServer(RuntimeRole enabledRole) {
        var context = new ApplicationContextRunner()
                .withUserConfiguration(
                        AccountPreferencesQueryAdapter.class,
                        ConsentService.class,
                        PrivacyJournalTestConfiguration.class)
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(new DriverManagerDataSource()))
                .withBean(AccountDirectory.class, () -> mock(AccountDirectory.class))
                .withBean(ConsentDecisionRepository.class, () -> mock(ConsentDecisionRepository.class))
                .withBean(AccountRepository.class, () -> mock(AccountRepository.class))
                .withBean(ConsentProperties.class, () -> new ConsentProperties(null));
        for (var role : RuntimeRole.values()) {
            context = context.withPropertyValues(role.property() + "=" + (role == enabledRole));
        }
        context.run(app -> {
            assertThat(app).hasNotFailed();
            if (enabledRole == RuntimeRole.SERVER) {
                assertThat(app).hasSingleBean(AccountPreferencesQuery.class);
                assertThat(app).hasSingleBean(ResearchParticipationQuery.class);
            } else {
                assertThat(app).doesNotHaveBean(AccountPreferencesQuery.class);
                assertThat(app).doesNotHaveBean(ResearchParticipationQuery.class);
            }
        });
    }

    @Test
    void shouldPropagateLookupFailureWhenAccountStorageIsUnavailable() {
        var accounts = mock(AccountDirectory.class);
        var adapter = new AccountPreferencesQueryAdapter(accounts, mock(ResearchParticipationQuery.class));
        when(accounts.find(7L)).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(() -> adapter.preferencesForAccount(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");
    }
}
