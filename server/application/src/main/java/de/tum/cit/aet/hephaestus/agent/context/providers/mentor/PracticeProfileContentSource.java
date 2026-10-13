package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.HephTurnRequest;
import de.tum.cit.aet.hephaestus.practices.spi.DeveloperPracticeStandings;
import de.tum.cit.aet.hephaestus.practices.spi.DeveloperPracticeStandings.Standing;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Materialises {@code inputs/context/practice_profile.json}: the developer's own standing in each practice, in the
 * words of their Practice profile. Grouped by practice group and listed by name, never by standing: a standing is
 * where the newest decided work stands, not a score, and the file gives Heph nothing to rank with. It names no
 * observation; the review history holds those.
 */
@Component
@RequiredArgsConstructor
public class PracticeProfileContentSource implements ContentSource {

    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "practice_profile.json";

    /** Fewer decided pieces of work than this make a standing an early read. */
    static final int EARLY_READ_BELOW = 3;

    private final DeveloperPracticeStandings standings;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof HephTurnRequest;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        HephTurnRequest req = (HephTurnRequest) request;
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(buildPayload(req.workspaceId(), req.developerId())));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize practice profile context", e);
        }
    }

    ObjectNode buildPayload(long workspaceId, long developerId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put(
                "about",
                "The developer's own standings as their Practice profile shows them, from the newest decided pieces "
                        + "of work in each practice. Not scores: never rank practices or people with them.");
        Map<String, ArrayNode> groups = new LinkedHashMap<>();
        ArrayNode groupArray = root.putArray("groups");
        standings.standings(workspaceId, developerId).stream()
                .sorted(Comparator.comparing(Standing::group, Comparator.nullsLast(String::compareTo))
                        .thenComparing(Standing::name)
                        .thenComparing(Standing::slug))
                .forEach(practice -> {
                    ArrayNode into = groups.computeIfAbsent(String.valueOf(practice.group()), key -> {
                        ObjectNode node = groupArray.addObject();
                        node.put("group", practice.group());
                        return node.putArray("practices");
                    });
                    describe(into.addObject(), practice);
                });
        return root;
    }

    private static void describe(ObjectNode node, Standing practice) {
        node.put("slug", practice.slug());
        node.put("name", practice.name());
        node.put("standing", practice.standing());
        if (practice.verdict()) {
            node.put("decidedPiecesOfWork", practice.decidedWork());
            node.put("earlyRead", practice.decidedWork() < EARLY_READ_BELOW);
        }
        if (practice.direction() != null) {
            node.put("direction", practice.direction());
        }
        node.put("pointsToWorkOn", practice.toWorkOn());
        node.put("strengthsNoted", practice.strengths());
        if (practice.holdsAs() != null) {
            node.put("holdsAs", practice.holdsAs());
        }
    }
}
