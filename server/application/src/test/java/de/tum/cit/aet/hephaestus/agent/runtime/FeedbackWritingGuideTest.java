package de.tum.cit.aet.hephaestus.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.mentor.host.HephTurnDispatcher;
import de.tum.cit.aet.hephaestus.practices.feedback.PracticePageFeedbackBody;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The shared writing guide as the prompts receive it, and its "no internal names or mechanics" rule against what
 * Hephaestus writes itself: the good examples in Hephaestus's prompt, which the model imitates, Hephaestus's own notices and the
 * practice-page card template. What a model writes is the live eval's to check.
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
    void shouldUseNoInternalTermsInTheTextHephaestusWritesItself() throws IllegalAccessException {
        List<String> fixed = new ArrayList<>();
        for (Field field : HephTurnDispatcher.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                field.setAccessible(true);
                fixed.add((String) Objects.requireNonNull(field.get(null)));
            }
        }
        assertThat(fixed).as("Hephaestus's own notices").isNotEmpty();
        fixed.add(PracticePageFeedbackBody.render("A title", "A body.", "A next step."));

        InternalFeedbackTerms.assertPlain(fixed);
    }

    @Test
    void shouldFailOnInternalTokensAndOnAPracticeSlugButPassPlainEnglish() {
        for (String reply : List.of(
                "Several pieces are AWAITING_APPROVAL or WITHHELD.",
                "The observation is NOT_MET.",
                "See results_history.json for that resultId.",
                "The describe-what-and-why review found a gap.")) {
            assertThatThrownBy(() -> InternalFeedbackTerms.assertPlain(List.of("Add a test for the 404.", reply)))
                    .as(reply)
                    .isInstanceOf(AssertionError.class);
        }
        InternalFeedbackTerms.assertPlain(
                List.of(
                        "Add a test for the 404.",
                        "Your feedback was delivered, and the retry is still pending, captured in a test that is withheld from CI."));
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = Objects.requireNonNull(FeedbackWritingGuideTest.class.getResourceAsStream(name))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
