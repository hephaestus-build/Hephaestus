package de.tum.cit.aet.hephaestus.core.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class PublicActivityPolicyServiceTest extends BaseUnitTest {
    @Mock
    private InstanceSettingsRepository settings;

    @Test
    void shouldUseTheConfiguredDefaultUntilAnAdministratorSetsAChoice() {
        var service = new PublicActivityPolicyService(settings, Optional.empty(), false);
        assertThat(service.allowed()).isFalse();
        var configured = new PublicActivityPolicyService(settings, Optional.empty(), true);
        assertThat(configured.allowed()).isTrue();
        var row = new InstanceSettings();
        row.setId(InstanceSettings.SINGLETON_ID);
        when(settings.findById(InstanceSettings.SINGLETON_ID)).thenReturn(Optional.of(row));
        assertThat(configured.allowed()).isTrue();
        row.setPublicActivityAllowed(false);
        assertThat(configured.allowed()).isFalse();
        row.setPublicActivityAllowed(true);
        assertThat(service.allowed()).isTrue();
    }

    @Test
    void shouldAllowEitherChoiceWithoutARequiredServerRoleBean() {
        var row = new InstanceSettings();
        when(settings.findById(InstanceSettings.SINGLETON_ID)).thenReturn(Optional.of(row));
        var service = new PublicActivityPolicyService(settings, Optional.empty(), false);
        assertThat(service.update(true)).isTrue();
        assertThat(row.getPublicActivityAllowed()).isTrue();
        assertThat(service.update(false)).isFalse();
        assertThat(row.getPublicActivityAllowed()).isFalse();
    }
}
