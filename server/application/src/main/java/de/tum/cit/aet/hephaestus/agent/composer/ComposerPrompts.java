package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.runtime.FeedbackWritingGuide;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

/** The composers' system prompts, read from the classpath: one shared style, and one prompt for each composer. */
final class ComposerPrompts {
    private ComposerPrompts() {}

    static String sharedStyle() {
        return prompt("feedback-style.md") + "\n\n" + FeedbackWritingGuide.text();
    }

    /** The system prompt of the review on the work, for the legacy composition and the work-place composer alike. */
    static String reviewSystem() {
        return sharedStyle() + "\n\n" + prompt("review-composer.md");
    }

    static String prompt(String name) {
        try {
            return new ClassPathResource("agent/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }
}
