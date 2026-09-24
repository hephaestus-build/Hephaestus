package de.tum.cit.aet.hephaestus.account;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@WorkspaceAgnostic("User-scoped practice-feedback preferences — not workspace-specific")
public class AccountPreferencesService {

    private static final Logger log = LoggerFactory.getLogger(AccountPreferencesService.class);

    private final UserPreferencesRepository userPreferencesRepository;

    public AccountPreferencesService(UserPreferencesRepository userPreferencesRepository) {
        this.userPreferencesRepository = userPreferencesRepository;
    }

    private UserPreferences loadOrCreatePreferences(User user) {
        return userPreferencesRepository.findByUserId(user.getId()).orElseGet(() -> {
            log.debug("Created default preferences: userLogin={}", user.getLogin());
            return userPreferencesRepository.save(new UserPreferences(user));
        });
    }

    @Transactional
    public UserSettingsDTO getUserSettings(User user) {
        log.debug("Fetching user settings: userLogin={}", user.getLogin());
        return toDTO(loadOrCreatePreferences(user));
    }

    @Transactional
    public UserSettingsDTO updateUserSettings(User user, UserSettingsDTO userSettings) {
        log.info("Updating user settings: userLogin={}", user.getLogin());
        UserPreferences preferences = loadOrCreatePreferences(user);

        preferences.setPracticeFeedbackDeliveryEnabled(Objects.requireNonNull(
                userSettings.practiceFeedbackDeliveryEnabled(), "practiceFeedbackDeliveryEnabled must not be null"));

        userPreferencesRepository.save(preferences);

        return toDTO(preferences);
    }

    private static UserSettingsDTO toDTO(UserPreferences preferences) {
        return new UserSettingsDTO(preferences.isPracticeFeedbackDeliveryEnabled());
    }
}
