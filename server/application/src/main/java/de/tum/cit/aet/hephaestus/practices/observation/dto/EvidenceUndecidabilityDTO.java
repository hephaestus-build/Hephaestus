package de.tum.cit.aet.hephaestus.practices.observation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * What the evidence left open, recorded when the review read it and still could not decide.
 *
 * <p>Read from {@code evidence.undecidability}, and absent unless both parts are there — the same rule
 * as {@link EvidenceSearchDTO}, for the same reason.
 */
@Schema(description = "What the review could not settle, and what would settle it")
public record EvidenceUndecidabilityDTO(
        @NonNull @Schema(description = "The question this work left open")
        String openQuestion,

        @NonNull @Schema(description = "What would have answered it")
        String wouldSettleIt) {

    static @Nullable EvidenceUndecidabilityDTO from(JsonNode undecidability) {
        if (!undecidability.isObject()) return null;
        String openQuestion = EvidenceWarrants.text(undecidability, "openQuestion");
        String wouldSettleIt = EvidenceWarrants.text(undecidability, "wouldSettleIt");
        return openQuestion == null || wouldSettleIt == null
                ? null
                : new EvidenceUndecidabilityDTO(openQuestion, wouldSettleIt);
    }
}
