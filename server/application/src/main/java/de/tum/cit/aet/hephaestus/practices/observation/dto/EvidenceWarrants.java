package de.tum.cit.aet.hephaestus.practices.observation.dto;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The reading rules the three evidence warrants share: a part is present only when it is a non-blank
 * string, and {@code consulted} is the list of source kinds the review says it read.
 *
 * <p>The review writes every part or the sandbox refuses the observation, so a missing one here means a
 * row written before the warrant existed. That reads as "no warrant recorded", never as a warrant with
 * a hole in it.
 */
final class EvidenceWarrants {

    private EvidenceWarrants() {}

    static @Nullable String text(JsonNode warrant, String field) {
        String value = warrant.path(field).asString(null);
        return value == null || value.isBlank() ? null : value;
    }

    static List<String> sourceKinds(JsonNode warrant) {
        JsonNode consulted = warrant.path("consulted");
        if (!consulted.isArray()) return List.of();
        List<String> sourceKinds = new ArrayList<>();
        for (JsonNode sourceKind : consulted) {
            String value = sourceKind.asString(null);
            if (value != null && !value.isBlank()) sourceKinds.add(value);
        }
        return List.copyOf(sourceKinds);
    }
}
