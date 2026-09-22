package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class MemberAiPreferencesAdapterTest extends BaseUnitTest {
    @Mock
    private WorkspaceOnboardingSettingsRepository settings;

    @Mock
    private AccountAiChoiceRepository choices;

    @Mock
    private AccountIdentityQuery identities;

    @Mock
    private UserRepository users;

    private MemberAiPreferencesAdapter preferences;

    @BeforeEach
    void setUp() {
        preferences = new MemberAiPreferencesAdapter(settings, choices, identities, users);
    }

    private void linkedDeveloper() {
        var provider = new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com");
        provider.setId(7L);
        var user = new User();
        user.setId(20L);
        user.setProvider(provider);
        user.setNativeId(123L);
        user.setLogin("renamed-user");
        when(users.findById(20L)).thenReturn(Optional.of(user));
        when(identities.resolveActiveAccountId(7L, "123", null)).thenReturn(Optional.of(10L));
    }

    private void chose(MemberAiChoice choice) {
        var row = new AccountAiChoice();
        row.setAccountId(10L);
        row.setAiChoice(choice);
        when(choices.findById(10L)).thenReturn(Optional.of(row));
    }

    @Test
    void shouldApplyTheAccountChoiceInEveryWorkspaceEvenWhereSetupIsHidden() {
        linkedDeveloper();
        chose(MemberAiChoice.NO_AI);
        assertThat(preferences.forDeveloper(1L, 20L).permitsAi()).isFalse();
        assertThat(preferences.forDeveloper(2L, 20L).permitsAi()).isFalse();
        verify(identities, times(2)).resolveActiveAccountId(7L, "123", null);
    }

    @Test
    void shouldCarryTheCeilingAsBindingOnceChosen() {
        linkedDeveloper();
        chose(MemberAiChoice.IN_HOUSE_ONLY);
        var decision = preferences.forDeveloper(1L, 20L);
        assertThat(decision.choiceRequired()).isTrue();
        assertThat(decision.choice()).isEqualTo(MemberAiChoice.IN_HOUSE_ONLY);
        assertThat(decision.permitsAi()).isTrue();
    }

    @Test
    void shouldRefuseUnlinkedDevelopersWhenWorkspaceRequiresAChoice() {
        var policy = new WorkspaceOnboardingSettings();
        policy.setAiChoiceRequired(true);
        when(settings.findByWorkspaceId(1L)).thenReturn(Optional.of(policy));
        assertThat(preferences.forDeveloper(1L, 20L).permitsAi()).isFalse();
        assertThat(preferences.forDeveloper(1L, null).permitsAi()).isFalse();
        verifyNoInteractions(choices);
    }

    @Test
    void shouldPreserveTheLegacyDefaultForAMemberWhoHasNotChosenWhereTheChoiceIsOptional() {
        linkedDeveloper();
        assertThat(preferences.forDeveloper(1L, 20L).choice()).isNull();
        assertThat(preferences.forDeveloper(1L, 20L).permitsAi()).isTrue();
        assertThat(preferences.forDeveloper(1L, null).permitsAi()).isTrue();
    }

    @Test
    void shouldTreatAnUnlinkedIdentityAsNotHavingAnswered() {
        linkedDeveloper();
        when(identities.resolveActiveAccountId(7L, "123", null)).thenReturn(Optional.empty());
        var policy = new WorkspaceOnboardingSettings();
        policy.setAiChoiceRequired(true);
        when(settings.findByWorkspaceId(1L)).thenReturn(Optional.of(policy));
        assertThat(preferences.forDeveloper(1L, 20L).permitsAi()).isFalse();
        assertThat(preferences.forDeveloper(2L, 20L).permitsAi()).isTrue();
        verifyNoInteractions(choices);
    }
}
