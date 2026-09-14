package de.tum.cit.aet.hephaestus.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.core.auth.spi.ConsentSource;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchConsentAudit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;

class AccountPreferencesServiceTest extends BaseUnitTest {
    @Mock
    private UserPreferencesRepository preferencesRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ObjectProvider<ResearchConsentAudit> auditProvider;

    @Mock
    private ResearchConsentAudit audit;

    private AccountPreferencesService service;

    @BeforeEach
    void setUp() {
        service = new AccountPreferencesService(preferencesRepository, userRepository, auditProvider);
    }

    @Test
    void shouldPersistAndAuditResearchOptOut() {
        User user = user();
        UserPreferences preferences = preferences(user, true);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(preferencesRepository.findByUserId(42L)).thenReturn(Optional.of(preferences));
        when(auditProvider.getIfAvailable()).thenReturn(audit);

        service.setForUserId(42L, false, ConsentSource.SLACK_APP_HOME);

        assertThat(preferences.isParticipateInResearch()).isFalse();
        verify(preferencesRepository).save(preferences);
        verify(audit).recordOptOut("octocat", ConsentSource.SLACK_APP_HOME);
    }

    @Test
    void shouldNotAuditResearchOptIn() {
        User user = user();
        UserPreferences preferences = preferences(user, false);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(preferencesRepository.findByUserId(42L)).thenReturn(Optional.of(preferences));

        service.setForUserId(42L, true, ConsentSource.SETTINGS_UI);

        assertThat(preferences.isParticipateInResearch()).isTrue();
        verifyNoInteractions(auditProvider);
    }

    @Test
    void shouldIgnoreUnknownActor() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());
        service.setForUserId(404L, false, ConsentSource.SLACK_APP_HOME);
        verify(preferencesRepository, never()).save(any());
        verifyNoInteractions(auditProvider);
    }

    private static User user() {
        User user = new User();
        user.setId(42L);
        user.setLogin("octocat");
        return user;
    }

    private static UserPreferences preferences(User user, boolean participates) {
        UserPreferences preferences = new UserPreferences(user);
        preferences.setParticipateInResearch(participates);
        return preferences;
    }
}
