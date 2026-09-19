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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every bundled practice is written as the decision procedure the authoring guide describes
 * (docs/admin/writing-practices.mdx, "Write the criteria as a decision procedure"): the same sections,
 * in the same order, under one size. The model reads a practice once beside the others of its group,
 * so the shape is what lets a small model apply it the same way every time; the measured difference
 * between a contract and a procedure is in the guide.
 */
class CatalogCriteriaShapeTest extends BaseUnitTest {

    /** The section headings, in order; each practice carries all of them. */
    static final List<String> SECTIONS = List.of(
            "## The standard", "## Occasion", "## Sources", "## Judge", "## Severity", "## Grounding", "## Defer");

    static final int MAX_CRITERIA_CHARS = 8_000;

    /** The four assessed cells; the Judge section decides each, as ordinary or as "no ordinary case". */
    static final List<String> CELLS = List.of("PRESENT/GOOD", "PRESENT/BAD", "ABSENT/GOOD", "ABSENT/BAD");

    @Test
    @DisplayName("every bundled practice opens with its behavior focus and carries the sections in order")
    void bundledPracticesFollowTheShape() throws IOException {
        List<String> failures = new ArrayList<>();
        for (JsonNode group : catalogue().path("groups")) {
            for (JsonNode practice : group.path("practices")) {
                String slug = practice.path("slug").asText();
                String criteria = practice.path("criteria").asText("");
                if (!criteria.startsWith("BEHAVIOR FOCUS:")) {
                    failures.add(slug + ": must open with 'BEHAVIOR FOCUS:'");
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
                // The runner refuses a cell the Judge section rules out, so a section that leaves a cell
                // unnamed leaves the session free to record the clean bill of an undesirable behaviour
                // as GOOD — the lapse it is not.
                String judge = sectionOf(criteria, "## Judge");
                for (String cell : CELLS) {
                    if (!judge.contains(cell)) {
                        failures.add(slug + ": the Judge section does not decide " + cell);
                    }
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
