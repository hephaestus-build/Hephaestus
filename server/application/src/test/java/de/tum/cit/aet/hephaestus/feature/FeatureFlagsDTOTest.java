package de.tum.cit.aet.hephaestus.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;

class FeatureFlagsDTOTest extends BaseUnitTest {

    @Mock
    private FeatureProperties featureProperties;

    @InjectMocks
    private FeatureFlagService featureFlagService;

    @Nested
    class DriftDetection {

        @Test
        void dtoFieldCountMatchesEnumCount() {
            assertThat(FeatureFlagsDTO.class.getRecordComponents())
                    .as("FeatureFlagsDTO must have one field per FeatureFlag constant")
                    .hasSize(FeatureFlag.values().length);
        }

        @Test
        void dtoFieldNamesMatchEnumNames() {
            Set<String> enumNames =
                    Arrays.stream(FeatureFlag.values()).map(Enum::name).collect(Collectors.toSet());

            Set<String> dtoNames = Arrays.stream(FeatureFlagsDTO.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .collect(Collectors.toSet());

            assertThat(dtoNames).isEqualTo(enumNames);
        }
    }

    @Nested
    class FromMapping {

        static Stream<FeatureFlag> allFlags() {
            return Arrays.stream(FeatureFlag.values());
        }

        @ParameterizedTest(name = "from() maps {0} to the correct DTO field")
        @MethodSource("allFlags")
        void fromMapsEachFlagCorrectly(FeatureFlag flag) throws Exception {
            // Stub only the target flag to return true, all others return false
            for (FeatureFlag f : FeatureFlag.values()) {
                if (f.kind() == FeatureFlag.Kind.CONFIG) {
                    lenient().when(featureProperties.isEnabled(f.key())).thenReturn(f == flag);
                }
            }

            // For ROLE flags, we cannot set SecurityContext per-flag in a simple
            // parameterized test, so we test the mapping direction differently:
            // Build the DTO and verify the field named after the flag matches the
            // service's isEnabled result for that flag.
            boolean expected = featureFlagService.isEnabled(flag);
            FeatureFlagsDTO dto = FeatureFlagsDTO.from(featureFlagService);

            // Use reflection to read the DTO field matching the flag's name
            RecordComponent component = Arrays.stream(FeatureFlagsDTO.class.getRecordComponents())
                    .filter(rc -> rc.getName().equals(flag.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No DTO field for " + flag.name()));

            boolean actual = (boolean) component.getAccessor().invoke(dto);
            assertThat(actual)
                    .as("DTO field %s should match service.isEnabled(%s)", flag.name(), flag)
                    .isEqualTo(expected);
        }

        @Test
        void fromMapsEnabledConfigFlag() {
            // Stub all CONFIG flags — from() calls isEnabled on every flag
            for (FeatureFlag f : FeatureFlag.values()) {
                if (f.kind() == FeatureFlag.Kind.CONFIG) {
                    lenient()
                            .when(featureProperties.isEnabled(f.key()))
                            .thenReturn(f == FeatureFlag.PAGE_EXPERIENCE_METRICS);
                }
            }

            FeatureFlagsDTO dto = FeatureFlagsDTO.from(featureFlagService);

            assertThat(dto.PAGE_EXPERIENCE_METRICS()).isTrue();
        }
    }
}
