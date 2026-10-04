package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks the shape defined in docs/admin/writing-practices.mdx: the criteria are guidance — the standard, the
 * sources, what to cite and what other practices own — and the judgment, never the criteria, decides the outcome.
 */
class CatalogCriteriaShapeTest extends BaseUnitTest {

    /** The section headings, in order; each practice carries all of them. */
    static final List<String> SECTIONS = List.of("## The standard", "## Sources", "## Grounding", "## Defer");

    /** Sections whose decisions moved into the judgment. */
    static final List<String> RETIRED_SECTIONS = List.of("## Occasion", "## Judge", "## Severity");

    static final int MAX_CRITERIA_CHARS = 8_000;

    private static final Pattern RESULT_LABEL =
            Pattern.compile("\\b(?:MET|NOT_MET|NOT_APPLICABLE|UNDETERMINED|MINOR|MAJOR|CRITICAL)\\b");

    @Test
    @DisplayName("every bundled practice states its review focus and carries the sections in order")
    void bundledPracticesFollowTheShape() throws IOException {
        List<String> failures = new ArrayList<>();
        for (JsonNode group : catalogue().path("groups")) {
            for (JsonNode practice : group.path("practices")) {
                String slug = practice.path("slug").asText();
                String criteria = practice.path("criteria").asText("");
                if (criteria.indexOf("REVIEW FOCUS:") < 0
                        || criteria.indexOf("REVIEW FOCUS:") > criteria.indexOf("\n## The standard")) {
                    failures.add(slug + ": must state 'REVIEW FOCUS:' before the standard");
                }
                int last = -1;
                for (String section : SECTIONS) {
                    int at = criteria.indexOf("\n" + section);
                    if (at < 0) {
                        failures.add(slug + ": missing section '" + section + "'");
                    } else if (at < last) {
                        failures.add(slug + ": section '" + section + "' is out of order");
                    } else {
                        last = at;
                    }
                }
                for (String retired : RETIRED_SECTIONS) {
                    if (criteria.contains("\n" + retired)) {
                        failures.add(slug + ": '" + retired + "' belongs in the judgment, not the criteria");
                    }
                }
                if (criteria.length() > MAX_CRITERIA_CHARS) {
                    failures.add(slug + ": " + criteria.length() + " characters, over " + MAX_CRITERIA_CHARS);
                }
                var label = RESULT_LABEL.matcher(criteria);
                if (label.find()) {
                    failures.add(slug + ": the criteria name the result '" + label.group() + "'; the rules decide it");
                }
                JsonNode rules = practice.path("judgment").path("rules");
                if (practice.has("insufficiencyReason")) {
                    continue;
                }
                for (String outcome : List.of("MET", "NOT_MET")) {
                    boolean decides = false;
                    for (JsonNode rule : rules)
                        decides |= outcome.equals(rule.path("outcome").asText());
                    if (!decides) failures.add(slug + ": no rule decides " + outcome);
                }
            }
        }
        assertThat(failures)
                .as("bundled practices off the guidance-and-judgment shape")
                .isEmpty();
    }

    private static JsonNode catalogue() throws IOException {
        try (InputStream in =
                CatalogCriteriaShapeTest.class.getClassLoader().getResourceAsStream("practices/default-catalog.json")) {
            assertThat(in)
                    .as("practices/default-catalog.json must be on the classpath")
                    .isNotNull();
            return new ObjectMapper().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
