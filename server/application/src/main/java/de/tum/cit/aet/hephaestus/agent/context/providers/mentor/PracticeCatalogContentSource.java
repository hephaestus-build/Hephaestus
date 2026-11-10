package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextRequest;
import de.tum.cit.aet.hephaestus.practices.PracticeGuidanceRules;
import de.tum.cit.aet.hephaestus.practices.spi.MentorPracticeQuery;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The workspace's practices with their criteria and guidance, in the catalog's own order, never a ranking. The file
 * has its own bound, well below the fetch cap: it lists whole practices up to {@value #MAX_PRACTICES} and
 * {@value #MAX_CATALOG_CHARS} characters, and names every practice it left out in {@code omittedPractices}.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PracticeCatalogContentSource implements ContentSource {
    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "practice_catalog.json";

    /** Practices listed in full; the rest are named in {@code omittedPractices}. */
    static final int MAX_PRACTICES = 20;

    static final int MAX_CATALOG_CHARS = 64_000;

    /** The texts of a practice that may be shortened, each named in {@code shortenedFields} when it is. */
    private static final List<String> SHORTENABLE =
            List.of("criteria", "whyItMatters", "whatGoodLooksLike", "guide", "visualDescription");

    private final MentorPracticeQuery practices;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(MentorContextRequest request) {
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(MentorContextRequest request, Map<String, byte[]> files) {
        files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(buildPayload(request.workspaceId())));
    }

    public ObjectNode buildPayload(Long workspaceId) {
        var current = practices.current(workspaceId);
        var root = objectMapper.createObjectNode();
        root.put("totalPractices", current.size());
        var rows = root.putArray("practices");
        for (var entry : current.subList(0, Math.min(current.size(), MAX_PRACTICES))) {
            var definition = entry.definition();
            var guidance = definition.guidance();
            rows.addObject()
                    .put("slug", entry.slug())
                    .put("displayName", definition.name())
                    .put("criteria", definition.criteria())
                    .put("whyItMatters", guidance.whyItMatters())
                    .put("whatGoodLooksLike", guidance.whatGoodLooksLike())
                    .put(
                            "visualDescription",
                            guidance.visual() == null ? null : guidance.visual().alt())
                    .put(
                            "guide",
                            guidance.guide() == null
                                    ? null
                                    : PracticeGuidanceRules.describeFigures(
                                            guidance.guide().markdown()));
        }
        // Whole practices first: a cut inside one would read as a complete criterion. The bound measures the whole
        // file, with its list of what it left out.
        ArrayNode omitted = root.putArray("omittedPractices");
        current.subList(rows.size(), current.size()).forEach(practice -> omitted.add(practice.slug()));
        root.put("truncated", !omitted.isEmpty());
        while (rows.size() > 1 && !fits(root)) {
            omitted.insert(0, rows.remove(rows.size() - 1).path("slug").asString());
            root.put("truncated", true);
        }
        if (!rows.isEmpty() && !fits(root)) {
            shorten(root, (ObjectNode) rows.get(0));
            root.put("truncated", true);
        }
        return root;
    }

    /** Fits the one practice left by halving its longest text until the file fits, naming each text it shortened. */
    private void shorten(ObjectNode root, ObjectNode practice) {
        ArrayNode shortened = practice.putArray("shortenedFields");
        while (!fits(root)) {
            Optional<String> found = SHORTENABLE.stream()
                    .filter(field -> practice.path(field).isString())
                    .max(Comparator.comparingInt(
                            field -> practice.path(field).asString().length()));
            if (found.isEmpty() || practice.path(found.get()).asString().isEmpty()) {
                return;
            }
            String longest = found.get();
            String text = practice.path(longest).asString();
            int keep = text.length() / 2;
            int end = keep > 0 && Character.isHighSurrogate(text.charAt(keep - 1)) ? keep - 1 : keep;
            practice.put(longest, text.substring(0, end));
            if (shortened.valueStream().noneMatch(name -> name.asString().equals(longest))) {
                shortened.add(longest);
            }
        }
    }

    private boolean fits(ObjectNode root) {
        return objectMapper.writeValueAsString(root).length() <= MAX_CATALOG_CHARS;
    }
}
