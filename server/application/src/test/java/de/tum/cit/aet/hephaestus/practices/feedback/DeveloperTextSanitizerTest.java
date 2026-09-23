package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeveloperTextSanitizerTest extends BaseUnitTest {

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
