package de.tum.cit.aet.hephaestus.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guards the "no internal names or mechanics" rule of the feedback writing guide against the example feedback that the
 * composer and Heph tests use, and against the good examples in Heph's prompt, which the model imitates. It checks
 * fixtures, not live model output.
 */
@Tag("unit")
class FeedbackWritingGuideFixturesTest {
    // Words such as "rejected" or "pipeline" stay out: feedback about the reader's own code can use them.
    private static final Pattern INTERNAL = Pattern.compile(
            "\\b(?:awaiting approval|withheld|ready for delivery|delivery state|context file|sandbox|practice slug|resultId|problemId|link_result|link_problem|show_feedback|model provider|server claims)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GOOD_EXAMPLE = Pattern.compile("Good: \"(.+?)\"", Pattern.DOTALL);
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void renderedFixturesAndHephExamplesDoNotExposeInternalTerms() throws Exception {
        List<String> slugs = practiceSlugs();
        JsonNode card = fixture("/agent/composer-card.json").path("prepared").path("page");
        List<String> texts = new ArrayList<>(List.of(
                card.path("title").asString(),
                card.path("body").asString(),
                card.path("nextStep").asString()));
        JsonNode review = fixture("/agent/golden-prompts/composition-turns/pull-request/review.json");
        texts.add(review.path("summary").path("body").asString());
        review.path("inline").forEach(note -> texts.add(note.path("body").asString()));
        for (String name : List.of("runner_link_result", "runner_link_problem", "runner_show_feedback")) {
            texts.add(fixture("/agent/mentor/pi-events/" + name + ".json")
                    .path("text")
                    .asString());
        }
        int before = texts.size();
        Matcher good = GOOD_EXAMPLE.matcher(resource("/agent/mentor/system.md"));
        while (good.find()) {
            texts.add(good.group(1));
        }
        assertThat(texts).as("Heph's good examples").hasSizeGreaterThan(before);
        for (String text : texts) {
            assertThat(text).isNotBlank();
            assertThat(INTERNAL.matcher(text).find()).as(text).isFalse();
            assertThat(slugs).as(text).noneMatch(text::contains);
        }
    }

    @Test
    void recognizesInternalTermsInTheAuditedReplies() {
        for (String reply : List.of(
                "It's not flagged ready for delivery.",
                "Several pieces are awaiting approval or withheld.",
                "The context file that would carry its cited code lines isn't readable.")) {
            assertThat(INTERNAL.matcher(reply).find()).as(reply).isTrue();
        }
    }

    private List<String> practiceSlugs() throws Exception {
        List<String> slugs = new ArrayList<>();
        fixture("/practices/default-catalog.json")
                .path("groups")
                .forEach(group -> group.path("practices")
                        .forEach(practice -> slugs.add(practice.path("slug").asString())));
        assertThat(slugs).isNotEmpty();
        return slugs;
    }

    private JsonNode fixture(String name) throws Exception {
        return mapper.readTree(resource(name));
    }

    private String resource(String name) throws Exception {
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream(name))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
