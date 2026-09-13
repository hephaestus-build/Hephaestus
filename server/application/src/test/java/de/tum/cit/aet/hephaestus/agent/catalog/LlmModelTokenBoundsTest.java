package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.validation.Validation;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class LlmModelTokenBoundsTest extends BaseUnitTest {

    private static final List<IntFunction<Object>> REQUESTS = List.of(
            value -> new CreateLlmModelRequestDTO(null, "Model", "model", value, value, null, null, null, null, null),
            value -> new UpdateLlmModelRequestDTO(null, value, value, null, null, null, null, null),
            value -> new CreateWorkspaceLlmModelRequestDTO(
                    null, "Model", "model", value, value, null, null, null, null, null, null, null, null, null, null,
                    null),
            value -> new UpdateWorkspaceLlmModelRequestDTO(
                    null, value, value, null, null, null, null, null, null, null, null, null, null, null));

    @Test
    void shouldRequirePositiveTokenBoundsForEveryModelRequest() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (IntFunction<Object> request : REQUESTS) {
                assertThat(validator.validate(request.apply(0)))
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactlyInAnyOrder("contextWindow", "maxOutputTokens");
                assertThat(validator.validate(request.apply(1))).isEmpty();
            }
        }
    }
}
