package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.curated.dto.CuratedPracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.CreatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.validation.Validation;
import java.util.Objects;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PracticeContainerValidationTest extends BaseUnitTest {
    @ParameterizedTest
    @CsvSource({
        "create, bindings",
        "update, bindings",
        "curated, bindings",
        "binding, needs",
        "policy, knownLimitations"
    })
    void shouldCascadeIntoElementsWithoutDeprecatedContainerValidation(String model, String property) {
        Class<?> type =
                switch (model) {
                    case "create" -> CreatePracticeRequestDTO.class;
                    case "update" -> UpdatePracticeRequestDTO.class;
                    case "curated" -> CuratedPracticeRequestDTO.class;
                    case "binding" -> PracticeBinding.class;
                    case "policy" -> PracticeAutomatedReviewPolicy.class;
                    default -> throw new IllegalArgumentException(model);
                };
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var descriptor = Objects.requireNonNull(
                    factory.getValidator().getConstraintsForClass(type).getConstraintsForProperty(property));
            assertThat(descriptor.isCascaded()).isFalse();
            assertThat(descriptor.getConstrainedContainerElementTypes())
                    .singleElement()
                    .satisfies(element -> assertThat(element.isCascaded()).isTrue());
        }
    }
}
