package de.tum.cit.aet.hephaestus.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AccountDirectoryService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountPreferencesServiceTest extends BaseUnitTest {

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final Account account = new Account("Octocat");
    private final AccountPreferencesService service =
            new AccountPreferencesService(new AccountDirectoryService(accounts, Clock.systemUTC(), event -> {}));

    AccountPreferencesServiceTest() {
        account.setId(42L);
        account.setTimeZone("Europe/Berlin");
        when(accounts.findByIdForUpdate(42L)).thenReturn(Optional.of(account));
        when(accounts.findById(42L)).thenReturn(Optional.of(account));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldChangeAccountDeliveryChoiceWhenSettingsAreSaved(boolean enabled) {
        var result = service.updateUserSettings(42L, new UpdateUserSettingsDTO(enabled, "Asia/Tokyo"));
        assertThat(result.practiceFeedbackDeliveryEnabled()).isEqualTo(enabled);
        assertThat(account.isFeedbackDeliveryEnabled()).isEqualTo(enabled);
        assertThat(result.timeZone()).isEqualTo("Asia/Tokyo");
        assertThat(account.getTimeZone()).isEqualTo("Asia/Tokyo");
    }
}
