package de.tum.cit.aet.hephaestus.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The shared writing guide as the prompts receive it, and its "no internal names or mechanics" rule against the good
 * examples in Heph's prompt, which the model imitates. {@code WorkerFeedbackComposerTest} checks the composer's
 * prepared feedback against the same terms.
 */
@Tag("unit")
class FeedbackWritingGuideTest {
    private static final Pattern GOOD_EXAMPLE = Pattern.compile("Good: \"(.+?)\"", Pattern.DOTALL);

    @Test
    void shouldGiveThePromptsTheWritingFeedbackSectionWhenItLoadsTheGuide() {
        assertThat(FeedbackWritingGuide.text())
                .startsWith("## Writing feedback\n")
                .contains("state the answer or concern first")
                .doesNotContain("## Retired words");
    }

    @Test
    void shouldReadTheGuideToTheEndWhenItIsTheLastSection() {
        assertThat(FeedbackWritingGuide.section("# Page\r\n\r\n## Writing feedback\r\n\r\nSay it plainly.\r\n"))
                .isEqualTo("## Writing feedback\n\nSay it plainly.");
    }

    @Test
    void shouldFailWhenThePageHasNoWritingFeedbackHeading() {
        assertThatThrownBy(() -> FeedbackWritingGuide.section("# Page\n\n## Retired words\n"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldUseNoInternalTermsInTheGoodExamplesOfHephsPrompt() throws IOException {
        List<String> examples = new ArrayList<>();
        Matcher good = GOOD_EXAMPLE.matcher(resource("/agent/mentor/system.md"));
        while (good.find()) {
            examples.add(good.group(1));
        }

        InternalFeedbackTerms.assertPlain(examples);
    }

    @Test
    void shouldFailOnTheAuditedRepliesAndOnAPracticeSlug() {
        for (String reply : List.of(
                "It's not flagged ready for delivery.",
                "Several pieces are awaiting approval or withheld.",
                "The context file that would carry its cited code lines isn't readable.",
                "The describe-what-and-why review found a gap.")) {
            assertThatThrownBy(() -> InternalFeedbackTerms.assertPlain(List.of("Add a test for the 404.", reply)))
                    .as(reply)
                    .isInstanceOf(AssertionError.class);
        }
        InternalFeedbackTerms.assertPlain(List.of("Add a test for the 404."));
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = Objects.requireNonNull(FeedbackWritingGuideTest.class.getResourceAsStream(name))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
