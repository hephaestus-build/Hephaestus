package de.tum.cit.aet.hephaestus.practices.observation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The frame of a search that came up empty, recorded when the review found the behaviour absent.
 *
 * <p>Read from {@code evidence.search}, where the review writes it. A warrant missing any of its three
 * parts is no warrant, so it reads as absent rather than as a half-answer a surface would have to
 * explain.
 */
@Schema(description = "Where the review looked when it found nothing")
public record EvidenceSearchDTO(
        @NonNull @Schema(description = "What the review looked for in this work")
        String lookedFor,

        @NonNull @Schema(description = "The sources the review searched, by source kind")
        List<String> consulted,

        @NonNull @Schema(description = "How far the search reached — the work this absence holds over")
        String boundary) {
    public EvidenceSearchDTO {
        consulted = List.copyOf(consulted);
    }

    static @Nullable EvidenceSearchDTO from(JsonNode search) {
        if (!search.isObject()) return null;
        String lookedFor = EvidenceWarrants.text(search, "lookedFor");
        String boundary = EvidenceWarrants.text(search, "boundary");
        List<String> consulted = EvidenceWarrants.sourceKinds(search);
        return lookedFor == null || boundary == null || consulted.isEmpty()
                ? null
                : new EvidenceSearchDTO(lookedFor, consulted, boundary);
    }
}
