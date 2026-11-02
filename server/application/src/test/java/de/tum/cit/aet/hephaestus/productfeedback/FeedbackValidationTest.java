package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class FeedbackValidationTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://example.org/path",
                "//example.org/path",
                "/page?token=secret",
                "/w/mara.lindqvist@example.edu/activity",
                "/page#secret",
                "/page\nsecret",
                "/page\u0085secret",
                "/page\u009fsecret",
                "/w/Alice/activity",
                "/w/team.name/activity",
                "/w//activity"
            })
    void shouldRejectNonLocalOrSensitivePageContext(String path) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator()
                            .validate(new FeedbackDTOs.FeedbackRequestDTO(
                                    ProductFeedback.Kind.BUG, "Bug", path, null, null)))
                    .isNotEmpty();
        }
    }

    @Test
    void shouldAcceptOmittedContextAndOrdinaryPageAndBrowserDetails() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new FeedbackDTOs.FeedbackRequestDTO(
                            ProductFeedback.Kind.FEEDBACK, "An idea", null, null, null)))
                    .isEmpty();
            assertThat(validator.validate(new FeedbackDTOs.FeedbackRequestDTO(
                            ProductFeedback.Kind.BUG,
                            "Bug",
                            "/w/$workspaceSlug/practice-profile",
                            "Mozilla/5.0 (X11; Linux x86_64)",
                            null)))
                    .isEmpty();
            assertThat(validator.validate(new FeedbackDTOs.FeedbackRequestDTO(
                            ProductFeedback.Kind.BUG, "Bug", "/w/team", "Mozilla\u0000", null)))
                    .isNotEmpty();
        }
    }
}
