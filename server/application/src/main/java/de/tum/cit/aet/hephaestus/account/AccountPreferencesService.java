package de.tum.cit.aet.hephaestus.account;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountTimeZone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@WorkspaceAgnostic("User-scoped practice-feedback preferences — not workspace-specific")
public class AccountPreferencesService {

    private static final Logger log = LoggerFactory.getLogger(AccountPreferencesService.class);

    private final UserPreferencesRepository userPreferencesRepository;
    private final AccountTimeZone accountTimeZone;

    public AccountPreferencesService(
            UserPreferencesRepository userPreferencesRepository, AccountTimeZone accountTimeZone) {
        this.userPreferencesRepository = userPreferencesRepository;
        this.accountTimeZone = accountTimeZone;
    }

    private UserPreferences loadOrCreatePreferences(User user) {
        return userPreferencesRepository.findByUserId(user.getId()).orElseGet(() -> {
            log.debug("Created default preferences: userLogin={}", user.getLogin());
            return userPreferencesRepository.save(new UserPreferences(user));
        });
    }

    @Transactional
    public UserSettingsDTO getUserSettings(User user, long accountId) {
        log.debug("Fetching user settings: userLogin={}", user.getLogin());
        return toDTO(
                loadOrCreatePreferences(user),
                accountTimeZone.timeZone(accountId).orElse(null));
    }

    /**
     * Saves the settings. The time zone belongs to the account, not to one SCM actor: it is one home for every
     * workspace, and the weekly digest reads it.
     */
    @Transactional
    public UserSettingsDTO updateUserSettings(User user, long accountId, UserSettingsDTO userSettings) {
        log.info("Updating user settings: userLogin={}", user.getLogin());
        String timeZone = userSettings.timeZone();
        UserPreferences preferences = loadOrCreatePreferences(user);

        preferences.setPracticeFeedbackDeliveryEnabled(Objects.requireNonNull(
                userSettings.practiceFeedbackDeliveryEnabled(), "practiceFeedbackDeliveryEnabled must not be null"));

        userPreferencesRepository.save(preferences);
        accountTimeZone.setTimeZone(accountId, timeZone);

        return toDTO(preferences, timeZone);
    }

    private static UserSettingsDTO toDTO(UserPreferences preferences, @Nullable String timeZone) {
        return new UserSettingsDTO(preferences.isPracticeFeedbackDeliveryEnabled(), timeZone);
    }
}
