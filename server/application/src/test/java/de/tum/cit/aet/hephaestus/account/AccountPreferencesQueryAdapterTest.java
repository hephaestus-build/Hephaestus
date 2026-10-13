package de.tum.cit.aet.hephaestus.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountTimeZone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.CurrentAccountUsers;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class AccountPreferencesQueryAdapterTest extends BaseUnitTest {

    @Mock
    private UserPreferencesRepository userPreferencesRepository;

    @Mock
    private CurrentAccountUsers accountUsers;

    @Mock
    private UserRepository users;

    @Mock
    private AccountIdentityQuery identities;

    @Mock
    private AccountTimeZone accountTimeZone;

    @Test
    void lookupFailureIsNotConvertedToMissingPreferences() {
        var adapter = new AccountPreferencesQueryAdapter(
                userPreferencesRepository, accountUsers, users, identities, accountTimeZone);
        when(userPreferencesRepository.findByUserId(7L)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> adapter.preferencesForUserId(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");
    }
}
