package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class FeedbackValidationTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://example.org/path",
                "//example.org/path",
                "/page?token=secret",
                "/page#secret",
                "/page\nsecret",
                "/page\u0085secret",
                "/page\u009fsecret"
            })
    void shouldRejectNonLocalOrSensitivePageContext(String path) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator()
                            .validate(new FeedbackDTOs.FeedbackRequestDTO(ProductFeedback.Kind.BUG, "Bug", path)))
                    .isNotEmpty();
        }
    }

    @Test
    void shouldAcceptOmittedContextAndAnOrdinaryLocalPath() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(
                            new FeedbackDTOs.FeedbackRequestDTO(ProductFeedback.Kind.FEEDBACK, "An idea", null)))
                    .isEmpty();
            assertThat(validator.validate(
                            new FeedbackDTOs.FeedbackRequestDTO(ProductFeedback.Kind.BUG, "Bug", "/w/team/practices")))
                    .isEmpty();
        }
    }

    @Test
    void shouldRejectNullAnswerValuesAtTheRequestBoundary() {
        var request = new ObjectMapper().readValue("{\"answers\":{\"q\":null}}", FeedbackDTOs.SubmitSurveyDTO.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request))
                    .singleElement()
                    .satisfies(violation ->
                            assertThat(violation.getPropertyPath().toString()).contains("answers", "q"));
        }
    }

    @Test
    void shouldRejectNullQuestionsAndNullOptionsBeforeTheyReachTheService() {
        var mapper = new ObjectMapper();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (String questions : List.of(
                    "[null]",
                    "[{\"id\":\"q\",\"prompt\":\"Question\",\"type\":\"TEXT\",\"options\":null,\"required\":false}]")) {
                var request = mapper.readValue(
                        "{\"title\":\"Survey\",\"description\":\"Purpose\",\"startsAt\":\"2026-01-01T00:00:00Z\",\"questions\":"
                                + questions + "}",
                        FeedbackDTOs.CreateSurveyDTO.class);
                assertThat(factory.getValidator().validate(request)).isNotEmpty();
            }
        }
    }
}
