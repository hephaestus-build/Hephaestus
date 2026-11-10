package de.tum.cit.aet.hephaestus.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.MockSecurityContextUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.security.core.context.SecurityContextHolder;

class FeatureFlagServiceTest extends BaseUnitTest {

    @Mock
    private FeatureProperties featureProperties;

    @InjectMocks
    private FeatureFlagService featureFlagService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    class RoleFlags {

        @Test
        void returnsTrueForAdminUser() {
            setSecurityContext("admin", FeatureFlag.ADMIN.key());

            assertThat(featureFlagService.isEnabled(FeatureFlag.ADMIN)).isTrue();
        }

        @Test
        void returnsFalseWhenUserLacksRole() {
            setSecurityContext("testuser", "some_other_role");

            assertThat(featureFlagService.isEnabled(FeatureFlag.ADMIN)).isFalse();
        }

        @Test
        void returnsFalseWithNoSecurityContext() {
            assertThat(featureFlagService.isEnabled(FeatureFlag.ADMIN)).isFalse();
        }
    }

    @Nested
    class ConfigFlags {

        @Test
        void returnsTrueWhenConfigEnabled() {
            when(featureProperties.isEnabled(FeatureFlag.PAGE_EXPERIENCE_METRICS.key()))
                    .thenReturn(true);

            assertThat(featureFlagService.isEnabled(FeatureFlag.PAGE_EXPERIENCE_METRICS))
                    .isTrue();
        }

        @Test
        void returnsFalseWhenConfigDisabled() {
            when(featureProperties.isEnabled(FeatureFlag.PAGE_EXPERIENCE_METRICS.key()))
                    .thenReturn(false);

            assertThat(featureFlagService.isEnabled(FeatureFlag.PAGE_EXPERIENCE_METRICS))
                    .isFalse();
        }
    }

    @Nested
    class NullSafety {

        @Test
        void throwsForNullFlag() {
            assertThatThrownBy(() -> featureFlagService.isEnabled(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("flag must not be null");
        }
    }

    private void setSecurityContext(String username, String... authorities) {
        SecurityContextHolder.setContext(
                MockSecurityContextUtils.createSecurityContext(username + "-id", authorities, "mock-token"));
    }
}
