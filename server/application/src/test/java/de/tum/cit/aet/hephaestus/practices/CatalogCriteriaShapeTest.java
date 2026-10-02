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
 * Checks the criteria sections and size limits defined in docs/admin/writing-practices.mdx.
 */
class CatalogCriteriaShapeTest extends BaseUnitTest {

    /** The section headings, in order; each practice carries all of them. */
    static final List<String> SECTIONS = List.of(
            "## The standard", "## Occasion", "## Sources", "## Judge", "## Severity", "## Grounding", "## Defer");

    static final int MAX_CRITERIA_CHARS = 8_000;

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
                if (criteria.length() > MAX_CRITERIA_CHARS) {
                    failures.add(slug + ": " + criteria.length() + " characters, over " + MAX_CRITERIA_CHARS);
                }
                String judge = sectionOf(criteria, "## Judge");
                for (String outcome : List.of("MET", "NOT_MET")) {
                    if (!Pattern.compile("(?m)^- " + outcome + "(?:[: ]|$)")
                            .matcher(judge)
                            .find()) {
                        failures.add(slug + ": the Judge section does not decide " + outcome);
                    }
                }
                if (!judge.contains("UNDETERMINED")) {
                    failures.add(slug + ": the Judge section does not say when the review is UNDETERMINED");
                }
                if (!(sectionOf(criteria, "## Occasion") + judge).contains("NOT_APPLICABLE")) {
                    failures.add(slug + ": neither the Occasion nor the Judge section says when it is NOT_APPLICABLE");
                }
            }
        }
        assertThat(failures)
                .as("bundled practices off the decision-procedure shape")
                .isEmpty();
    }

    private static String sectionOf(String criteria, String heading) {
        int start = criteria.indexOf("\n" + heading);
        if (start < 0) return "";
        int end = criteria.indexOf("\n## ", start + 1);
        return end < 0 ? criteria.substring(start) : criteria.substring(start, end);
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
