package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeveloperTextSanitizerTest extends BaseUnitTest {

    @Test
    void shouldRemoveOutcomeLabelsWithoutRemovingTheActionableAdvice() {
        for (String outcome : new String[] {"MET", "NOT_MET", "NOT_APPLICABLE", "UNDETERMINED"}) {
            assertThat(DeveloperTextSanitizer.sanitize(
                            "The outcome is " + outcome + ". Add a test for the retry path."))
                    .isEqualTo("Add a test for the retry path.");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Track the review asks you defer in a linked issue.",
                "Keep each deferred-review-request gap on its own open line.",
                "When deferring a check, name the follow-up issue."
            })
    void shouldKeepActionableAdviceAboutDeferredWork(String advice) {
        assertThat(DeveloperTextSanitizer.sanitize(advice)).isEqualTo(advice);
    }

    @Test
    @DisplayName("a leading-dot name keeps the space before it while a dangling punctuation mark loses it")
    void shouldKeepTheSpaceBeforeALeadingDotNameWhenSanitizing() {
        assertThat(DeveloperTextSanitizer.sanitize(
                        "Move the analyze work into a .task modifier, where .task was available ."))
                .isEqualTo("Move the analyze work into a .task modifier, where .task was available.");
        assertThat(DeveloperTextSanitizer.sanitize("Add the secret to .gitignore , then rotate it"))
                .isEqualTo("Add the secret to .gitignore, then rotate it");
    }
}
