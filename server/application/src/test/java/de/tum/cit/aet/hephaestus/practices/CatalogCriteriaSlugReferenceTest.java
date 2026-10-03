package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CatalogCriteriaSlugReferenceTest extends BaseUnitTest {

    private static final Pattern SLUG_SHAPED = Pattern.compile("\\b[a-z]+(?:-[a-z]+){2,}\\b");

    @Test
    @DisplayName("no criteria string names a slug that isn't a real practice slug (truncations and phantoms)")
    void everyCriteriaSlugReferenceResolvesToARealPractice() throws IOException {
        Map<String, JsonNode> practices = loadPractices();
        Set<String> realSlugs = new TreeSet<>(practices.keySet());

        Set<String> offenders = new TreeSet<>();
        for (Map.Entry<String, JsonNode> e : practices.entrySet()) {
            String criteria = e.getValue().path("criteria").asText("");
            Matcher m = SLUG_SHAPED.matcher(criteria);
            while (m.find()) {
                String token = m.group();
                if (realSlugs.contains(token)) {
                    continue;
                }
                // Only flag tokens that look like a real slug reference: a segment-prefix truncation of an
                // actual practice slug. A plain English compound (e.g. "end-to-end", "line-by-line") is not a
                // prefix of any practice slug, so it is left alone — this keeps the guard free of false
                // positives while still catching the dangerous "drops the tail of a real slug" mistake.
                String truncationOf = realSlugTruncatedBy(token, realSlugs);
                if (truncationOf != null) {
                    offenders.add(token + " (in " + e.getKey() + " -> should be " + truncationOf + ")");
                }
            }
        }

        assertThat(offenders)
                .as(
                        "every slug-shaped criteria reference must name a real practice slug; these are truncations of a real slug")
                .isEmpty();

        // Belt-and-braces: these known phantom slugs must never appear, even though they are not prefixes
        // of any real slug (so the truncation check above would miss them).
        // Match them only as whole slug tokens (not followed by another '-' segment) so a real slug that
        // legitimately extends one of them (e.g. avoids-unsafe-panics-and-chosen-crashes) is not flagged.
        String rawCatalogue = readCatalogue();
        for (String phantom : Set.of("keeps-secrets-out", "avoids-unsafe-panics", "exposed-credential-material")) {
            Pattern asWholeSlug = Pattern.compile(Pattern.quote(phantom) + "(?![a-z-])");
            assertThat(asWholeSlug.matcher(rawCatalogue).find())
                    .as("the phantom slug '%s' must not be named in any criteria", phantom)
                    .isFalse();
        }
    }

    /** Returns the real slug that {@code token} is a strict segment-prefix of, or {@code null}. */
    private static @Nullable String realSlugTruncatedBy(String token, Set<String> realSlugs) {
        String prefix = token + "-";
        for (String slug : realSlugs) {
            if (slug.startsWith(prefix)) {
                return slug;
            }
        }
        return null;
    }

    private static Map<String, JsonNode> loadPractices() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(readCatalogue());
        Map<String, JsonNode> practices = new LinkedHashMap<>();
        for (JsonNode group : root.path("groups")) {
            for (JsonNode practice : group.path("practices")) {
                practices.put(practice.path("slug").asText(), practice);
            }
        }
        assertThat(practices).as("catalogue must declare practices").isNotEmpty();
        return practices;
    }

    private static String readCatalogue() throws IOException {
        try (InputStream in = CatalogCriteriaSlugReferenceTest.class
                .getClassLoader()
                .getResourceAsStream("practices/default-catalog.json")) {
            assertThat(in)
                    .as("practices/default-catalog.json must be on the classpath")
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
