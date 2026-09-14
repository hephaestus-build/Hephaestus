package de.tum.cit.aet.hephaestus.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountPreferencesServiceTest extends BaseUnitTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldChangeOnlyPracticeFeedbackDelivery(boolean enabled) {
        var repository = mock(UserPreferencesRepository.class);
        var service = new AccountPreferencesService(repository);
        var user = new User();
        user.setId(42L);
        user.setLogin("octocat");
        var preferences = new UserPreferences(user);
        preferences.setParticipateInResearch(true);
        when(repository.findByUserId(42L)).thenReturn(Optional.of(preferences));

        var updated = service.updateUserSettings(user, new UserSettingsDTO(enabled));

        assertThat(updated.practiceFeedbackDeliveryEnabled()).isEqualTo(enabled);
        assertThat(preferences.isPracticeFeedbackDeliveryEnabled()).isEqualTo(enabled);
        assertThat(preferences.isParticipateInResearch()).isTrue();
        verify(repository).save(preferences);
    }
}
