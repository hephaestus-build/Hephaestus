package de.tum.cit.aet.hephaestus.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The internal tokens that the feedback writing guide keeps out of what a developer reads: the case-sensitive terms in
 * {@code agent/internal-feedback-terms.json}, which the webapp's card copy test reads too, and every bundled practice
 * slug.
 */
public final class InternalFeedbackTerms {
    private static final JsonMapper MAPPER = new JsonMapper();
    private static final Pattern TERMS = terms();
    private static final List<String> SLUGS = slugs();

    private InternalFeedbackTerms() {}

    /** The first internal term or practice slug in {@code text}, if any. */
    public static Optional<String> find(String text) {
        Matcher term = TERMS.matcher(text);
        if (term.find()) {
            return Optional.of(term.group());
        }
        return SLUGS.stream().filter(text::contains).findFirst();
    }

    /** Fails on the first text that is blank or names an internal term or a practice slug. */
    public static void assertPlain(Collection<String> texts) {
        assertThat(texts).isNotEmpty();
        for (String text : texts) {
            assertThat(text).isNotBlank();
            assertThat(find(text)).as(text).isEmpty();
        }
    }

    private static Pattern terms() {
        List<String> terms = new ArrayList<>();
        json("/agent/internal-feedback-terms.json")
                .path("terms")
                .forEach(term -> terms.add(Pattern.quote(term.asString())));
        assertThat(terms).isNotEmpty();
        return Pattern.compile(terms.stream().collect(Collectors.joining("|", "\\b(?:", ")\\b")));
    }

    private static List<String> slugs() {
        List<String> slugs = new ArrayList<>();
        json("/practices/default-catalog.json")
                .path("groups")
                .forEach(group -> group.path("practices")
                        .forEach(practice -> slugs.add(practice.path("slug").asString())));
        assertThat(slugs).isNotEmpty();
        return List.copyOf(slugs);
    }

    private static JsonNode json(String name) {
        try (InputStream input = Objects.requireNonNull(InternalFeedbackTerms.class.getResourceAsStream(name))) {
            return MAPPER.readTree(input);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
