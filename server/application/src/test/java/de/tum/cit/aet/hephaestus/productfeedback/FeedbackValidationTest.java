package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class FeedbackValidationTest {
    // Mirrors spring.jackson.deserialization.fail-on-null-for-primitives=false from application.yml.
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

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
                            .validate(new FeedbackDTOs.FeedbackRequestDTO(ProductFeedback.Kind.BUG, "Bug", path, null)))
                    .isNotEmpty();
        }
    }

    @Test
    void shouldAcceptOmittedContextAndOrdinaryPageAndBrowserDetails() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(
                            new FeedbackDTOs.FeedbackRequestDTO(ProductFeedback.Kind.FEEDBACK, "An idea", null, null)))
                    .isEmpty();
            assertThat(validator.validate(new FeedbackDTOs.FeedbackRequestDTO(
                            ProductFeedback.Kind.BUG, "Bug", "/w/team/practices", "Mozilla/5.0 (X11; Linux x86_64)")))
                    .isEmpty();
            assertThat(validator.validate(new FeedbackDTOs.FeedbackRequestDTO(
                            ProductFeedback.Kind.BUG, "Bug", "/w/team", "Mozilla\u0000")))
                    .isNotEmpty();
        }
    }

    @Test
    void shouldRejectNullAnswersAtTheRequestBoundary() {
        var request = MAPPER.readValue("{\"answers\":[null]}", FeedbackDTOs.SubmitSurveyDTO.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request))
                    .singleElement()
                    .satisfies(violation ->
                            assertThat(violation.getPropertyPath().toString()).contains("answers"));
        }
    }

    @Test
    void shouldRejectAnEndBeforeTheStart() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        var questions = List.of(new FeedbackDTOs.QuestionDTO(
                "q", "Question", FeedbackDTOs.QuestionType.TEXT, List.of(), false, false, null, null));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new FeedbackDTOs.CreateSurveyDTO(
                            "Survey", "Purpose", questions, null, start, start.minusSeconds(1))))
                    .singleElement()
                    .satisfies(
                            violation -> assertThat(violation.getMessage()).isEqualTo("endsAt must be after startsAt"));
            assertThat(validator.validate(new FeedbackDTOs.SurveyEditDTO("Survey", "Purpose", start, start, true)))
                    .isNotEmpty();
            assertThat(validator.validate(new FeedbackDTOs.SurveyEditDTO("Survey", "Purpose", start, null, true)))
                    .isEmpty();
        }
    }

    @Test
    void shouldRejectNullQuestionsAndNullOptionsBeforeTheyReachTheService() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (String questions : List.of(
                    "[null]",
                    "[{\"id\":\"q\",\"prompt\":\"Question\",\"type\":\"TEXT\",\"options\":null,\"required\":false}]")) {
                var request = MAPPER.readValue(
                        "{\"title\":\"Survey\",\"description\":\"Purpose\",\"startsAt\":\"2026-01-01T00:00:00Z\",\"questions\":"
                                + questions + "}",
                        FeedbackDTOs.CreateSurveyDTO.class);
                assertThat(factory.getValidator().validate(request)).isNotEmpty();
            }
        }
    }
}
