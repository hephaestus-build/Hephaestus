package de.tum.cit.aet.hephaestus.practices.observation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Verified, source-bound evidence for an observation: the quotes it rests on, and — at most one of them
 * — the warrant its outcome owes beyond those quotes. Which warrant is recorded follows the outcome: a
 * behaviour found absent records its search, a practice with nothing to judge records why, and an
 * undecided one records what it could not settle.
 */
@Schema(description = "Verified, source-bound evidence for an observation")
public record ObservationEvidenceDTO(
        @NonNull List<EvidenceCitationDTO> citations,
        @Nullable String detector,

        @Nullable
        @Schema(description = "Where the review looked when it found nothing; null unless it recorded a search")
        EvidenceSearchDTO search,

        @Nullable
        @Schema(description = "Why this practice had nothing to judge here; null unless the review recorded a reason")
        EvidenceInapplicabilityDTO inapplicability,

        @Nullable @Schema(description = "What the review could not settle; null unless it recorded an open question")
        EvidenceUndecidabilityDTO undecidability) {
    public ObservationEvidenceDTO {
        citations = List.copyOf(citations);
        if (citations.isEmpty()) {
            throw new IllegalArgumentException("Observation evidence requires citations");
        }
    }

    public static @Nullable ObservationEvidenceDTO from(@Nullable JsonNode evidence) {
        if (evidence == null || !evidence.isObject()) return null;
        String detector = evidence.path("detector").asString(null);
        List<EvidenceCitationDTO> citations = evidence.path("citations")
                .valueStream()
                .map(EvidenceCitationDTO::from)
                .toList();
        return new ObservationEvidenceDTO(
                citations,
                detector,
                EvidenceSearchDTO.from(evidence.path("search")),
                EvidenceInapplicabilityDTO.from(evidence.path("inapplicability")),
                EvidenceUndecidabilityDTO.from(evidence.path("undecidability")));
    }
}
