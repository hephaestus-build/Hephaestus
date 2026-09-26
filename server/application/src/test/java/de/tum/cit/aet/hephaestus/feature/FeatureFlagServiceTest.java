package de.tum.cit.aet.hephaestus.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.MockSecurityContextUtils;
import java.util.Map;
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
        void returnsTrueWhenUserHasRole() {
            setSecurityContext("testuser", FeatureFlag.NOTIFICATION_ACCESS.key());

            assertThat(featureFlagService.isEnabled(FeatureFlag.NOTIFICATION_ACCESS))
                    .isTrue();
        }

        @Test
        void returnsFalseWhenUserLacksRole() {
            setSecurityContext("testuser", FeatureFlag.NOTIFICATION_ACCESS.key());

            assertThat(featureFlagService.isEnabled(FeatureFlag.ADMIN)).isFalse();
        }

        @Test
        void returnsFalseWithNoSecurityContext() {
            assertThat(featureFlagService.isEnabled(FeatureFlag.NOTIFICATION_ACCESS))
                    .isFalse();
        }

        @Test
        void returnsTrueForAdminUser() {
            setSecurityContext("admin", FeatureFlag.ADMIN.key());

            assertThat(featureFlagService.isEnabled(FeatureFlag.ADMIN)).isTrue();
        }

        @Test
        void returnsCorrectFlagsWithMultipleAuthorities() {
            setSecurityContext("poweruser", FeatureFlag.ADMIN.key(), FeatureFlag.NOTIFICATION_ACCESS.key());

            assertThat(featureFlagService.isEnabled(FeatureFlag.ADMIN)).isTrue();
            assertThat(featureFlagService.isEnabled(FeatureFlag.NOTIFICATION_ACCESS))
                    .isTrue();
        }
    }

    @Nested
    class ConfigFlags {

        @Test
        void returnsTrueWhenConfigEnabled() {
            when(featureProperties.isEnabled(FeatureFlag.GITLAB_WORKSPACE_CREATION.key()))
                    .thenReturn(true);

            assertThat(featureFlagService.isEnabled(FeatureFlag.GITLAB_WORKSPACE_CREATION))
                    .isTrue();
        }

        @Test
        void returnsFalseWhenConfigDisabled() {
            when(featureProperties.isEnabled(FeatureFlag.GITLAB_WORKSPACE_CREATION.key()))
                    .thenReturn(false);

            assertThat(featureFlagService.isEnabled(FeatureFlag.GITLAB_WORKSPACE_CREATION))
                    .isFalse();
        }
    }

    @Nested
    class AllEnabled {

        @Test
        void returnsTrueWhenAllEnabled() {
            setSecurityContext("admin", FeatureFlag.ADMIN.key());
            when(featureProperties.isEnabled(FeatureFlag.GITLAB_WORKSPACE_CREATION.key()))
                    .thenReturn(true);

            assertThat(featureFlagService.allEnabled(FeatureFlag.ADMIN, FeatureFlag.GITLAB_WORKSPACE_CREATION))
                    .isTrue();
        }

        @Test
        void returnsFalseWhenOneDisabled() {
            setSecurityContext("admin", FeatureFlag.ADMIN.key());
            when(featureProperties.isEnabled(FeatureFlag.GITLAB_WORKSPACE_CREATION.key()))
                    .thenReturn(false);

            assertThat(featureFlagService.allEnabled(FeatureFlag.ADMIN, FeatureFlag.GITLAB_WORKSPACE_CREATION))
                    .isFalse();
        }

        @Test
        void returnsTrueForEmpty() {
            assertThat(featureFlagService.allEnabled()).isTrue();
        }
    }

    @Nested
    class AnyEnabled {

        @Test
        void returnsTrueWhenOneEnabled() {
            // ADMIN role flag is checked first and returns true (short-circuits)
            setSecurityContext("admin", FeatureFlag.ADMIN.key());

            assertThat(featureFlagService.anyEnabled(FeatureFlag.ADMIN, FeatureFlag.GITLAB_WORKSPACE_CREATION))
                    .isTrue();
        }

        @Test
        void returnsFalseWhenNoneEnabled() {
            // No security context → ROLE flags return false
            // CONFIG flags also return false (Mockito defaults unstubbed boolean to false)
            assertThat(featureFlagService.anyEnabled(FeatureFlag.ADMIN, FeatureFlag.GITLAB_WORKSPACE_CREATION))
                    .isFalse();
        }

        @Test
        void returnsFalseForEmpty() {
            assertThat(featureFlagService.anyEnabled()).isFalse();
        }
    }

    @Nested
    class EvaluateAll {

        @Test
        void returnsAllFlags() {
            setSecurityContext("testuser", FeatureFlag.NOTIFICATION_ACCESS.key());
            when(featureProperties.isEnabled(FeatureFlag.GITLAB_WORKSPACE_CREATION.key()))
                    .thenReturn(true);

            Map<FeatureFlag, Boolean> result = featureFlagService.evaluateAll();

            assertThat(result).hasSize(FeatureFlag.values().length);
            assertThat(result.get(FeatureFlag.NOTIFICATION_ACCESS)).isTrue();
            assertThat(result.get(FeatureFlag.ADMIN)).isFalse();
            assertThat(result.get(FeatureFlag.GITLAB_WORKSPACE_CREATION)).isTrue();
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
                MockSecurityContextUtils.createSecurityContext(username, username + "-id", authorities, "mock-token"));
    }
}
