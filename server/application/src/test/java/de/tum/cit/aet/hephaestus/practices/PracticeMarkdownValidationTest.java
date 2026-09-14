package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.validation.Validation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PracticeMarkdownValidationTest extends BaseUnitTest {
    @ParameterizedTest
    @ValueSource(strings = {"criteria", "whyItMatters", "whatGoodLooksLike"})
    void acceptsMultilineMarkdownAndOmittedFieldsButRejectsBlankUpdates(String property) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String text :
                    new String[] {"# Heading\n\n- First check\n- Second check", "\r\nExample\r\n", "Single line"}) {
                assertThat(validator.validateValue(UpdatePracticeRequestDTO.class, property, text))
                        .isEmpty();
            }
            assertThat(validator.validateValue(UpdatePracticeRequestDTO.class, property, null))
                    .isEmpty();
            for (String text : new String[] {"", " ", "\r\n\t "}) {
                assertThat(validator.validateValue(UpdatePracticeRequestDTO.class, property, text))
                        .hasSize(1);
            }
        }
    }
}
