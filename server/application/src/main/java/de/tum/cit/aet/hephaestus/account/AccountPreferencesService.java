package de.tum.cit.aet.hephaestus.account;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.ConsentSource;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchConsentAudit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@WorkspaceAgnostic("User-scoped preferences and research consent — not workspace-specific")
public class AccountPreferencesService {

    private static final Logger log = LoggerFactory.getLogger(AccountPreferencesService.class);

    private final UserPreferencesRepository userPreferencesRepository;
    private final UserRepository userRepository;
    // The audit adapter exists only in the server runtime; this service also loads in worker and webhook runtimes.
    private final ObjectProvider<ResearchConsentAudit> researchConsentAuditProvider;

    public AccountPreferencesService(
            UserPreferencesRepository userPreferencesRepository,
            UserRepository userRepository,
            ObjectProvider<ResearchConsentAudit> researchConsentAuditProvider) {
        this.userPreferencesRepository = userPreferencesRepository;
        this.userRepository = userRepository;
        this.researchConsentAuditProvider = researchConsentAuditProvider;
    }

    @Transactional
    public UserPreferences getOrCreatePreferences(User user) {
        return loadOrCreatePreferences(user);
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

        boolean participatesInResearch =
                Objects.requireNonNull(userSettings.participateInResearch(), "participateInResearch must not be null");
        preferences.setParticipateInResearch(participatesInResearch);
        userPreferencesRepository.save(preferences);

        return toDTO(preferences);
    }

    /** Updates research participation for the verified actor; an erased actor is a no-op. */
    @Transactional
    public void setForUserId(long userId, boolean participate, ConsentSource source) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            log.warn("research-consent: no user for userId={} (source={}); nothing to persist", userId, source);
            return;
        }
        User user = userOpt.get();
        UserPreferences preferences = loadOrCreatePreferences(user);
        boolean previousParticipation = preferences.isParticipateInResearch();
        preferences.setParticipateInResearch(participate);
        userPreferencesRepository.save(preferences);

        if (previousParticipation && !participate) {
            writeOptOutAuditEvent(user, source);
        }
    }

    private void writeOptOutAuditEvent(User user, ConsentSource source) {
        ResearchConsentAudit audit = researchConsentAuditProvider.getIfAvailable();
        if (audit != null) {
            audit.recordOptOut(user.getLogin(), source);
        }
    }

    private static UserSettingsDTO toDTO(UserPreferences preferences) {
        return new UserSettingsDTO(
                preferences.isParticipateInResearch(), preferences.isPracticeFeedbackDeliveryEnabled());
    }
}
