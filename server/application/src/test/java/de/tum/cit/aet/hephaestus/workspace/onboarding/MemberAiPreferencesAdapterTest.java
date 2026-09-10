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
    private WorkspaceMemberOnboardingRepository members;

    @Mock
    private AccountIdentityQuery identities;

    @Mock
    private UserRepository users;

    private MemberAiPreferencesAdapter preferences;

    @BeforeEach
    void setUp() {
        preferences = new MemberAiPreferencesAdapter(settings, members, identities, users);
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

    @Test
    void shouldResolveTheStableIdentityAndPreserveNoAiWhenWelcomeIsHidden() {
        linkedDeveloper();
        var row = new WorkspaceMemberOnboarding();
        row.setAiChoice(MemberAiChoice.NO_AI);
        when(members.findByWorkspace_IdAndAccountId(1L, 10L)).thenReturn(Optional.of(row));
        assertThat(preferences.forDeveloper(1L, 20L).permitsAi()).isFalse();
        assertThat(preferences.forDeveloper(2L, 20L).permitsAi()).isTrue();
        verify(identities, times(2)).resolveActiveAccountId(7L, "123", null);
    }

    @Test
    void shouldNotTreatDismissalAsAnAiChoiceEvenIfWelcomeIsLaterHidden() {
        linkedDeveloper();
        when(members.findByWorkspace_IdAndAccountId(1L, 10L)).thenReturn(Optional.of(new WorkspaceMemberOnboarding()));
        var result = preferences.forDeveloper(1L, 20L);
        assertThat(result.choice()).isNull();
        assertThat(result.permitsAi()).isFalse();
    }

    @Test
    void shouldRefuseUnlinkedDevelopersWhenWorkspaceRequiresAChoice() {
        var policy = new WorkspaceOnboardingSettings();
        policy.setAiChoiceRequired(true);
        when(settings.findByWorkspaceId(1L)).thenReturn(Optional.of(policy));
        assertThat(preferences.forDeveloper(1L, 20L).permitsAi()).isFalse();
        assertThat(preferences.forDeveloper(1L, null).permitsAi()).isFalse();
        verifyNoInteractions(members);
    }
}
