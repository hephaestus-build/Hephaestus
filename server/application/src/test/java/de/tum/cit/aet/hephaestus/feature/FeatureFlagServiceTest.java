package de.tum.cit.aet.hephaestus.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

class FeatureFlagServiceTest extends BaseUnitTest {

    @Mock
    private FeatureProperties featureProperties;

    @InjectMocks
    private FeatureFlagService featureFlagService;

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
}
